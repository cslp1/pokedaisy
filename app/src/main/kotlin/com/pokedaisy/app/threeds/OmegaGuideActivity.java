package com.pokedaisy.app.threeds;

import android.app.*;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Searchable, offline, location-by-location manual guide. It does not invent live progress. */
public final class OmegaGuideActivity extends Activity {
    private JSONArray chapters;
    private SharedPreferences progress;
    private LinearLayout entries;
    private EditText search;
    private TextView count;
    private ScrollView scroll;
    private String expanded="";
    private int px(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);progress=getSharedPreferences("omega_guide",MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(px(12),px(12),px(12),px(12));root.setBackgroundColor(0xffe2e5dc);setContentView(root);
        label(root,"OMEGA RUBY · STORY GUIDE",22,true);
        label(root,"70 chapters · 280 steps. Manual progress; not checked through a full playthrough. The game is paused while this page is open.",14,false);
        count=label(root,"",15,true);
        LinearLayout nav=new LinearLayout(this);root.addView(nav);Button back=new Button(this);back.setText("Back");nav.addView(back);back.setOnClickListener(v->finish());
        Button next=new Button(this);next.setText("Next unfinished step");nav.addView(next);next.setOnClickListener(v->{for(int i=0;i<chapters.length();i++){JSONObject c=chapters.optJSONObject(i);if(!done(c)){expanded=c.optString("id");search.setText(c.optString("title"));return;}}Toast.makeText(this,"All story steps checked",Toast.LENGTH_SHORT).show();});
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(0xff263d2d);search.setHintTextColor(0xff566650);search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff527349));search.setHint("Search a location, HM, item or obstacle");root.addView(search);
        scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);scroll.addView(entries);
        try(InputStream in=getAssets().open("guides/omega-ruby.json")){StringBuilder json=new StringBuilder();BufferedReader r=new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));String line;while((line=r.readLine())!=null)json.append(line);chapters=new JSONObject(json.toString()).getJSONArray("chapters");}
        catch(Exception e){label(entries,"The guide could not be loaded: "+e.getMessage(),18,false);return;}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int before,int c){render();}});render();
    }
    private TextView label(LinearLayout p,String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(0xff343d35);t.setTextSize(size);t.setPadding(px(10),px(8),px(10),px(8));if(bold)t.setTypeface(null,Typeface.BOLD);p.addView(t);return t;}
    private String key(JSONObject c,int step){return c.optString("id")+"-"+step;}
    private boolean done(JSONObject c){JSONArray steps=c.optJSONArray("steps");for(int i=0;i<steps.length();i++)if(!progress.getBoolean(key(c,i),false))return false;return true;}
    private void summary(){int total=0,checked=0;for(int i=0;i<chapters.length();i++){JSONObject c=chapters.optJSONObject(i);for(int j=0;j<c.optJSONArray("steps").length();j++){total++;if(progress.getBoolean(key(c,j),false))checked++;}}count.setText(checked+" / "+total+" steps checked · Progress stays on this device");}
    private void render(){entries.removeAllViews();summary();String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int found=0;
        for(int i=0;i<chapters.length();i++){
            JSONObject c=chapters.optJSONObject(i);if(!query.isEmpty()&&!c.toString().toLowerCase(Locale.ROOT).contains(query))continue;found++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);GradientDrawable bg=new GradientDrawable();bg.setColor(0xfff8f9f3);bg.setStroke(px(2),0xff727c6c);bg.setCornerRadius(px(6));card.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(10);entries.addView(card,lp);
            Button title=new Button(this);title.setAllCaps(false);title.setText((done(c)?"✓ ":"")+(i+1)+". "+c.optString("title"));title.setTextColor(0xff243923);title.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffd3dccb));card.addView(title);String id=c.optString("id");title.setOnClickListener(v->{expanded=expanded.equals(id)?"":id;render();});
            if(!expanded.equals(id)&&query.isEmpty())continue;
            label(card,"BEFORE THIS: "+c.optString("gate"),14,true);
            JSONArray steps=c.optJSONArray("steps");for(int j=0;j<steps.length();j++){int n=j;CheckBox step=new CheckBox(this);step.setText(steps.optString(j));step.setTextSize(17);step.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff456b39));step.setTextColor(0xff273b2e);step.setPadding(px(8),px(8),px(8),px(8));step.setChecked(progress.getBoolean(key(c,j),false));card.addView(step);step.setOnCheckedChangeListener((b,value)->{progress.edit().putBoolean(key(c,n),value).apply();summary();title.setText((done(c)?"✓ ":"")+c.optString("title"));});}
            if(!c.optString("extra").isEmpty()){label(card,"USEFUL / IF YOU'RE STUCK",14,true);label(card,c.optString("extra"),16,false);}
            if(!c.optString("next").isEmpty())label(card,"NEXT: "+c.optString("next"),16,true);
        }
        if(found==0)label(entries,"No matching chapter. Try a town, route or field move.",18,false);
    }
}
