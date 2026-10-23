package com.pokedaisy.app.threeds;

import android.app.*;
import android.content.*;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Explicitly experimental 3DS player, isolated from the mGBA/melonDS process. */
public final class ThreeDsActivity extends Activity {
    private GLSurfaceView surface;
    private TextView status,party;
    private volatile boolean partyEnabled=false,starting=false,importing=false,background=false,omegaRuby=false;
    private LinearLayout root,controls;
    private volatile boolean running=false,closing=false;
    private int held=0;
    private String lastError="";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,12,12,12);root.setBackgroundColor(0xffe6e8d9);setContentView(root);
        status=new TextView(this);status.setTextColor(0xff343b33);status.setTextSize(17);status.setPadding(12,12,12,12);status.setBackgroundColor(0xffffffff);root.addView(status);
        status.setText("NEW · 3DS EXPERIMENTAL\nEmbedded Azahar 2126.2. Game compatibility and performance are unverified. Live party is an opt-in experiment; other companion reads are not implemented.");
        LinearLayout menu=new LinearLayout(this);root.addView(menu);
        button(menu,"Open 3DS game",this::pick);button(menu,"Omega Ruby guide",()->startActivity(new Intent(this,OmegaGuideActivity.class)));button(menu,"Back",this::finish);
        CheckBox live=new CheckBox(this);live.setText("NEW · Experimental live party");live.setTextColor(0xff343b33);live.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff456b39));root.addView(live);
        party=new TextView(this);party.setTextColor(0xff343b33);party.setTextSize(14);party.setPadding(12,4,12,4);party.setVisibility(View.GONE);root.addView(party);
        live.setOnCheckedChangeListener((button,checked)->{partyEnabled=checked;party.setVisibility(checked?View.VISIBLE:View.GONE);party.setText("Waiting for an Omega Ruby game session. This reader is not verified on a tablet yet.");});
        if(Build.VERSION.SDK_INT<29){status.append("\nThis preview requires Android 10 or newer.");return;}
        try{Class.forName(ThreeDsNative.class.getName());}catch(Throwable e){status.append("\n3DS engine unavailable: "+e.getMessage());return;}
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(new GLSurfaceView.Renderer(){
            int width,height,frames;
            public void onSurfaceCreated(GL10 gl,EGLConfig config){android.opengl.GLES30.glClearColor(0,0,0,1);}
            public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;}
            public void onDrawFrame(GL10 gl){
                if(running&&!closing){ThreeDsNative.frame(width,height);if(++frames%60==0){if(partyEnabled&&omegaRuby){String snapshot=Gen6Party.describe(ThreeDsNative.partySlots());main.post(()->{if(!closing)party.setText(snapshot);});}String e=ThreeDsNative.error();if(e!=null&&!e.equals(lastError)){lastError=e;main.post(()->status.setText("3DS EXPERIMENTAL\n"+e));}}}
                else android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT);
            }
        });
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        surface.setOnTouchListener((v,event)->{
            // Native 1x stacked layout is 400 x 480, letterboxed inside the viewport.
            float scale=Math.min(v.getWidth()/400f,v.getHeight()/480f),w=400*scale,h=480*scale;
            float x=(event.getX()-(v.getWidth()-w)/2)/w,y=(event.getY()-(v.getHeight()-h)/2)/h;
            boolean down=event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL&&x>=0&&x<=1&&y>=0&&y<=1;
            ThreeDsNative.pointer((int)(Math.max(0,Math.min(1,x))*65534-32767),(int)(Math.max(0,Math.min(1,y))*65534-32767),down);return true;
        });
        Button resume=new Button(this);resume.setText("Resume imported game");root.addView(resume);resume.setOnClickListener(v->{File game=new File(getFilesDir(),"3ds/content/game.cci");if(game.isFile()&&!running)startGame(game);else if(!running)status.setText("Import your game first.");});

        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);root.addView(controls);
        View stick=new View(this){
            final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            float x=0,y=0;
            protected void onDraw(android.graphics.Canvas c){float r=Math.min(getWidth(),getHeight())*.4f,cx=getWidth()/2f,cy=getHeight()/2f;paint.setColor(0xff929e8d);c.drawCircle(cx,cy,r,paint);paint.setColor(0xff344d38);c.drawCircle(cx+x*r,cy+y*r,r*.25f,paint);}
            public boolean onTouchEvent(MotionEvent e){float r=Math.min(getWidth(),getHeight())*.4f;if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){x=0;y=0;}else{x=(e.getX()-getWidth()/2f)/r;y=(e.getY()-getHeight()/2f)/r;float length=(float)Math.hypot(x,y);if(length>1){x/=length;y/=length;}}ThreeDsNative.analog((int)(x*32767),(int)(y*32767));invalidate();return true;}
        };stick.setContentDescription("Circle Pad: drag gently to sneak");controls.addView(stick,new LinearLayout.LayoutParams(-1,(int)(110*getResources().getDisplayMetrics().density)));
        padRow(new String[]{"L","UP","X","R"},new int[]{10,4,9,11});padRow(new String[]{"LEFT","DOWN","RIGHT","Y","A","B"},new int[]{6,5,7,1,8,0});padRow(new String[]{"SELECT","START"},new int[]{2,3});
    }
    private void button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->action.run());}
    private void padRow(String[] names,int[] ids){LinearLayout row=new LinearLayout(this);controls.addView(row);for(int i=0;i<names.length;i++){final int bit=1<<ids[i];Button b=new Button(this);b.setText(names[i]);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnTouchListener((v,e)->{int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN)held|=bit;else if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL)held&=~bit;ThreeDsNative.buttons(held);return true;});}}
    private void pick(){if(surface==null){status.append("\nThe 3DS engine is unavailable on this device.");return;}if(running||starting||importing){status.setText("Wait for the current import or close the running 3DS session first.");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,300);}
    @Override public void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request!=300||result!=RESULT_OK||intent==null||intent.getData()==null)return;
        if(closing||importing||starting||running)return;
        importing=true;
        status.setText("Copying the 3DS game into private storage. Large games may take several minutes.");
        io.execute(()->{
            File dir=new File(getFilesDir(),"3ds/content");dir.mkdirs();File staging=new File(dir,"game.part"),game=new File(dir,"game.cci");
            try(InputStream in=getContentResolver().openInputStream(intent.getData());FileOutputStream out=new FileOutputStream(staging)){
                if(in==null)throw new IOException("Cannot open the selected game.");byte[] b=new byte[1024*1024];long total=0;int count;
                while((count=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Import cancelled.");total+=count;if(total>4L*1024*1024*1024)throw new IOException("The selected file is larger than a supported 3DS cartridge dump.");if(dir.getUsableSpace()<count+32L*1024*1024)throw new IOException("Not enough free storage to copy this game.");out.write(b,0,count);}out.getFD().sync();
                if(total<512)throw new IOException("This is not a 3DS cartridge dump.");
            }catch(Exception e){staging.delete();main.post(()->{importing=false;if(!closing)status.setText("Could not import: "+e.getMessage());});return;}
            try{ThreeDsCartHeader.validate(staging);}
            catch(Exception e){staging.delete();main.post(()->{importing=false;if(!closing)status.setText(e.getMessage());});return;}

            if(!staging.renameTo(game)){staging.delete();main.post(()->{importing=false;if(!closing)status.setText("Could not store the imported game.");});return;}
            if(isDestroyed()||closing)return;
            main.post(()->{importing=false;startGame(game);});
        });
    }
    private void startGame(File game){
        if(surface==null||running||starting||importing||closing)return;
        try{omegaRuby="CTR-P-ECRA".equals(ThreeDsCartHeader.validate(game));}catch(IOException e){status.setText(e.getMessage());return;}
        starting=true;
        File system=new File(getFilesDir(),"3ds/system"),saves=new File(getFilesDir(),"3ds/saves");system.mkdirs();saves.mkdirs();status.setText("Starting the 3DS engine…");
        surface.queueEvent(()->{
            if(closing){starting=false;main.post(ThreeDsActivity.super::finish);return;}
            String failure=ThreeDsNative.start(game.getAbsolutePath(),system.getAbsolutePath(),saves.getAbsolutePath());
            running=failure==null;starting=false;
            if(closing){ThreeDsNative.stop();running=false;main.post(ThreeDsActivity.super::finish);return;}
            ThreeDsNative.pause(background);
            main.post(()->{if(!closing)status.setText(failure==null?"3DS EXPERIMENTAL · Running in Daisy\nSave normally inside the game. Live party is experimental; bag and battle readers are unfinished.":"3DS could not start: "+failure);});
        });
    }
    @Override protected void onPause(){background=true;if(surface!=null){held=0;ThreeDsNative.pause(true);surface.onPause();}super.onPause();}
    @Override protected void onResume(){super.onResume();background=false;if(surface!=null){surface.onResume();ThreeDsNative.pause(false);}}
    @Override public void finish(){closing=true;if(surface!=null&&(running||starting)){surface.queueEvent(()->{ThreeDsNative.stop();running=false;main.post(super::finish);});}else super.finish();}
    @Override protected void onDestroy(){closing=true;io.shutdownNow();super.onDestroy();}
}
