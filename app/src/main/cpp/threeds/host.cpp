#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include "libretro.h"

namespace {
void* library=nullptr;
std::string system_dir,save_dir,content_path,error;
std::mutex error_lock;
std::map<std::string,std::string> options;
retro_hw_render_callback hw{};
retro_frame_time_callback frame_time{};
bool initialized=false,loaded=false,context_ready=false;
std::atomic<bool> paused{false};
std::atomic<unsigned> buttons{0};
std::atomic<int> analog_x{0},analog_y{0};
std::atomic<int> pointer_x{0},pointer_y{0},pointer_down{0};
GLuint fbo=0,texture=0,depth=0;
unsigned frame_width=400,frame_height=480;
AAudioStream* audio=nullptr;
constexpr unsigned RING=1<<17;
int16_t samples[RING];
std::atomic<unsigned> written{0},read_at{0};
void (*init)(); void (*deinit)(); void (*run)(); void (*unload)();
bool (*load)(const retro_game_info*);
void (*avinfo)(retro_system_av_info*);
void message(const std::string& s){std::lock_guard<std::mutex> lock(error_lock);error=s;__android_log_print(ANDROID_LOG_ERROR,"Daisy3DS","%s",s.c_str());}
void log_cb(retro_log_level level,const char* format,...){char b[2048];va_list args;va_start(args,format);vsnprintf(b,sizeof b,format,args);va_end(args);__android_log_print(level>=RETRO_LOG_ERROR?ANDROID_LOG_ERROR:ANDROID_LOG_INFO,"Azahar","%s",b);}
uintptr_t framebuffer(){return fbo;}
retro_proc_address_t procedure(const char* name){auto p=eglGetProcAddress(name);return p?reinterpret_cast<retro_proc_address_t>(p):reinterpret_cast<retro_proc_address_t>(dlsym(RTLD_DEFAULT,name));}
bool environment(unsigned command,void* data){
    switch(command){
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data)=system_dir.c_str();return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data)=save_dir.c_str();return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*static_cast<unsigned*>(data)=2;return true;
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:{auto* o=static_cast<retro_core_options_v2*>(data);for(auto* p=o->definitions;p&&p->key;++p)if(!options.count(p->key))options[p->key]=p->default_value?p->default_value:p->values[0].value;return true;}
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:{for(auto* p=static_cast<retro_core_option_definition*>(data);p&&p->key;++p)if(!options.count(p->key))options[p->key]=p->default_value?p->default_value:p->values[0].value;return true;}
    case RETRO_ENVIRONMENT_GET_VARIABLE:{auto* v=static_cast<retro_variable*>(data);auto it=options.find(v->key);v->value=it==options.end()?nullptr:it->second.c_str();return v->value;}
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*static_cast<bool*>(data)=false;return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:return *static_cast<retro_pixel_format*>(data)==RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:*static_cast<unsigned*>(data)=RETRO_HW_CONTEXT_OPENGLES3;return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER:{auto* cb=static_cast<retro_hw_render_callback*>(data);if(cb->context_type!=RETRO_HW_CONTEXT_OPENGLES3&&cb->context_type!=RETRO_HW_CONTEXT_OPENGLES_VERSION){message("The 3DS core requested an unsupported graphics context.");return false;}cb->get_current_framebuffer=framebuffer;cb->get_proc_address=procedure;hw=*cb;return true;}
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:static_cast<retro_log_callback*>(data)->log=log_cb;return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:*static_cast<bool*>(data)=true;return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:*static_cast<unsigned*>(data)=RETRO_LANGUAGE_ENGLISH;return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:return true;
    case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:*static_cast<uint64_t*>(data)=(1ULL<<RETRO_DEVICE_JOYPAD)|(1ULL<<RETRO_DEVICE_ANALOG)|(1ULL<<RETRO_DEVICE_POINTER);return true;
    case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:frame_time=*static_cast<retro_frame_time_callback*>(data);return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE:message(static_cast<retro_message*>(data)->msg);return true;
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT:message(static_cast<retro_message_ext*>(data)->msg);return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:{auto* g=static_cast<retro_game_geometry*>(data);frame_width=g->base_width;frame_height=g->base_height;return true;}
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:return true;
    default:return false;
    }
}
size_t audio_batch(const int16_t* data,size_t frames){unsigned w=written.load(std::memory_order_relaxed),r=read_at.load(std::memory_order_acquire);unsigned count=static_cast<unsigned>(frames*2);if(count>RING-(w-r))return frames;for(unsigned i=0;i<count;i++)samples[(w+i)&(RING-1)]=data[i];written.store(w+count,std::memory_order_release);return frames;}
void audio_sample(int16_t left,int16_t right){int16_t pair[]={left,right};audio_batch(pair,1);}
aaudio_data_callback_result_t audio_callback(AAudioStream*,void*,void* target,int32_t frames){auto* out=static_cast<int16_t*>(target);unsigned r=read_at.load(std::memory_order_relaxed),w=written.load(std::memory_order_acquire);for(int i=0;i<frames*2;i++)out[i]=!paused&&r<w?samples[(r++)&(RING-1)]:0;read_at.store(r,std::memory_order_release);return AAUDIO_CALLBACK_RESULT_CONTINUE;}
void poll(){}
int16_t input(unsigned port,unsigned device,unsigned index,unsigned id){if(port)return 0;if(device==RETRO_DEVICE_JOYPAD){unsigned bits=buttons.load();return id==RETRO_DEVICE_ID_JOYPAD_MASK?static_cast<int16_t>(bits):id<16?((bits>>id)&1):0;}if(device==RETRO_DEVICE_ANALOG&&index==RETRO_DEVICE_INDEX_ANALOG_LEFT)return id==RETRO_DEVICE_ID_ANALOG_X?analog_x.load():analog_y.load();if(device==RETRO_DEVICE_POINTER){if(index)return 0;switch(id){case RETRO_DEVICE_ID_POINTER_X:return pointer_x;case RETRO_DEVICE_ID_POINTER_Y:return pointer_y;case RETRO_DEVICE_ID_POINTER_PRESSED:return pointer_down;case RETRO_DEVICE_ID_POINTER_COUNT:return pointer_down;}}return 0;}
void video(const void* data,unsigned w,unsigned h,size_t){if(data==RETRO_HW_FRAME_BUFFER_VALID){frame_width=w;frame_height=h;}else if(data)message("This Android core requires hardware rendering.");}

std::string string(JNIEnv* env,jstring s){const char* p=env->GetStringUTFChars(s,nullptr);std::string out(p);env->ReleaseStringUTFChars(s,p);return out;}
void stop(){
    if(audio){AAudioStream_requestStop(audio);AAudioStream_close(audio);audio=nullptr;}
    if(loaded){unload();loaded=false;}if(context_ready&&hw.context_destroy)hw.context_destroy();context_ready=false;
    if(initialized){deinit();initialized=false;}
    if(fbo)glDeleteFramebuffers(1,&fbo);if(texture)glDeleteTextures(1,&texture);if(depth)glDeleteRenderbuffers(1,&depth);fbo=texture=depth=0;
    buttons=0;pointer_down=0;analog_x=0;analog_y=0;
    // Keep the core mapped for the lifetime of the dedicated emulator process.
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_start(JNIEnv* env,jclass,jstring path,jstring system,jstring saves){
    stop();{std::lock_guard<std::mutex> lock(error_lock);error.clear();}
    GLint major=0,minor=0;glGetIntegerv(GL_MAJOR_VERSION,&major);glGetIntegerv(GL_MINOR_VERSION,&minor);
    if(major<3||(major==3&&minor<2))return env->NewStringUTF("This 3DS preview needs OpenGL ES 3.2. This device or emulator does not expose it.");
    system_dir=string(env,system);save_dir=string(env,saves);content_path=string(env,path);
    if(!library)library=dlopen("libazahar_libretro.so",RTLD_NOW|RTLD_LOCAL);
    if(!library)return env->NewStringUTF(dlerror());
#define SYMBOL(name,variable) variable=reinterpret_cast<decltype(variable)>(dlsym(library,"retro_" name));if(!variable)return env->NewStringUTF("Missing 3DS emulator entry point: " name)
    SYMBOL("init",init);SYMBOL("deinit",deinit);SYMBOL("run",run);SYMBOL("load_game",load);SYMBOL("unload_game",unload);SYMBOL("get_system_av_info",avinfo);
#define SETTER(name,type,callback) {auto f=reinterpret_cast<void(*)(type)>(dlsym(library,"retro_set_" name));if(!f)return env->NewStringUTF("Missing 3DS emulator callback: " name);f(callback);}
    options={{"citra_graphics_api","OpenGL"},{"citra_resolution_factor","1"},{"citra_layout_option","default"},{"citra_use_libretro_save_path","LibRetro Default"},{"citra_enable_touch_touchscreen","enabled"},{"citra_use_virtual_sd","enabled"}};
    SETTER("environment",retro_environment_t,environment);SETTER("video_refresh",retro_video_refresh_t,video);SETTER("audio_sample",retro_audio_sample_t,audio_sample);SETTER("audio_sample_batch",retro_audio_sample_batch_t,audio_batch);SETTER("input_poll",retro_input_poll_t,poll);SETTER("input_state",retro_input_state_t,input);
    init();initialized=true;retro_game_info game{content_path.c_str(),nullptr,0,nullptr};
    if(!load(&game)){std::string cause;{std::lock_guard<std::mutex> lock(error_lock);cause=error;}stop();return env->NewStringUTF(cause.empty()?"The 3DS core could not open this game. Check the decrypted game dump and required system files.":cause.c_str());}loaded=true;
    retro_system_av_info av{};avinfo(&av);frame_width=av.geometry.base_width;frame_height=av.geometry.base_height;
    // The core advertises a 10x maximum; this host fixes resolution at 1x.
    unsigned width=frame_width,height=frame_height;
    if(width<1||height<1||width>4096||height>4096){stop();return env->NewStringUTF("Unsupported 3DS framebuffer size.");}
    glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glGenRenderbuffers(1,&depth);glBindRenderbuffer(GL_RENDERBUFFER,depth);glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,width,height);
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_RENDERBUFFER,depth);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){stop();return env->NewStringUTF("Could not create the 3DS graphics buffer.");}
    if(hw.context_reset){hw.context_reset();context_ready=true;}
    AAudioStreamBuilder* builder=nullptr;AAudio_createStreamBuilder(&builder);
    if(builder){AAudioStreamBuilder_setDirection(builder,AAUDIO_DIRECTION_OUTPUT);AAudioStreamBuilder_setFormat(builder,AAUDIO_FORMAT_PCM_I16);AAudioStreamBuilder_setChannelCount(builder,2);AAudioStreamBuilder_setSampleRate(builder,static_cast<int32_t>(av.timing.sample_rate));AAudioStreamBuilder_setDataCallback(builder,audio_callback,nullptr);if(AAudioStreamBuilder_openStream(builder,&audio)==AAUDIO_OK)AAudioStream_requestStart(audio);AAudioStreamBuilder_delete(builder);}
    written=0;read_at=0;paused=false;return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_frame(JNIEnv*,jclass,jint w,jint h){
    if(!loaded||paused)return;if(frame_time.callback)frame_time.callback(frame_time.reference);run();
    if(!fbo||!frame_width||!frame_height)return;
    GLint old_read,old_draw;glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,&old_read);glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING,&old_draw);GLboolean scissor=glIsEnabled(GL_SCISSOR_TEST);glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,0);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
    float scale=std::min(static_cast<float>(w)/frame_width,static_cast<float>(h)/frame_height);int dw=frame_width*scale,dh=frame_height*scale;
    glBlitFramebuffer(0,0,frame_width,frame_height,(w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2,GL_COLOR_BUFFER_BIT,GL_LINEAR);
    glBindFramebuffer(GL_READ_FRAMEBUFFER,old_read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,old_draw);if(scissor)glEnable(GL_SCISSOR_TEST);
}
extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_buttons(JNIEnv*,jclass,jint bits){buttons=bits;}
extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_pointer(JNIEnv*,jclass,jint x,jint y,jboolean down){pointer_x=x;pointer_y=y;pointer_down=down;}
extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_pause(JNIEnv*,jclass,jboolean value){paused=value;if(value){buttons=0;pointer_down=0;analog_x=0;analog_y=0;}}
extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_stop(JNIEnv*,jclass){stop();}
extern "C" JNIEXPORT jstring JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_error(JNIEnv* env,jclass){std::lock_guard<std::mutex> lock(error_lock);return error.empty()?nullptr:env->NewStringUTF(error.c_str());}

extern "C" JNIEXPORT void JNICALL Java_com_pokedaisy_app_threeds_ThreeDsNative_analog(JNIEnv*,jclass,jint x,jint y){analog_x=x;analog_y=y;}
