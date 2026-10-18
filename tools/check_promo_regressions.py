"""Exercise compiled production filters with JSON + fake host responses (no device).

Run :app:assembleDebug first. Requires Android Studio (STUDIO_HOME may override).
Host/Xposed fakes test failure handling; they do not verify a real LSPosed session.
"""
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
STUDIO = Path(os.environ.get("STUDIO_HOME", "C:/Program Files/Android/Android Studio"))
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk")))
OUT = ROOT / "build/promo-regression"
OUT.mkdir(parents=True, exist_ok=True)

sources = {
    "com/yilink/jumpclean/config/ConfigManager.java": r'''
package com.yilink.jumpclean.config;
public class ConfigManager {
    public static final ConfigManager INSTANCE = new ConfigManager();
    public int count;
    public final java.util.Map<String, Integer> intPrefs = new java.util.HashMap<>();
    public final java.util.Map<String, Boolean> boolPrefs = new java.util.HashMap<>();
    public int getIntPref(String key, int fallback) { return intPrefs.getOrDefault(key, fallback); }
    public boolean getBooleanPref(String key, boolean fallback) { return boolPrefs.getOrDefault(key, fallback); }
    public void recordOfficialPromoBlocked() { count++; }
    public android.content.Context getValidAppContext() { return null; }
    public java.util.List<Object> getBlockedMatchers(android.content.Context c) { return java.util.List.of(); }
    public void log(String s) {}
}
''',
    "de/robv/android/xposed/XposedHelpers.java": r'''
package de.robv.android.xposed;
public class XposedHelpers {
    public static Object callMethod(Object target, String name, Object... args) {
        return invoke(target.getClass(), target, name, args);
    }
    public static Object callStaticMethod(Class<?> cls, String name, Object... args) {
        return invoke(cls, null, name, args);
    }
    static Object invoke(Class<?> cls, Object target, String name, Object[] args) {
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try { return m.invoke(target, args); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        }
        throw new RuntimeException("No method: " + name);
    }
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name.equals("okhttp3.ResponseBody") ? "Regression$Body" : "Regression$Media"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Object getStaticObjectField(Class<?> cls, String name) { throw new RuntimeException("Injected failure"); }
}
''',
    "Regression.java": r'''
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import com.yilink.jumpclean.config.ConfigManager;

public class Regression {
    static boolean failCreate;
    static int checks;
    static final ClassLoader LOADER = Regression.class.getClassLoader();
    static final String JSON = "{\"data\":[{\"ads\":[{\"adType\":8,\"material\":{\"customNickname\":\"Jump小酱\"}},{\"adType\":101},{\"adType\":106},{\"adType\":999},{\"customNickname\":\"Jump小酱\"},null,\"future\"]},{\"future\":true},null,42]}";
    public static class Media { public static Object parse(String s) { return new Media(); } }
    public static class Body {
        String text; boolean closed; long size;
        Body(String t) { text=t; size=t.length(); }
        public long contentLength() { return size; }
        public Object contentType() { return new Media(); }
        public String string() { if(closed) throw new IllegalStateException("Consumed"); closed=true; return text; }
        public void close() { closed=true; }
        public static Body create(Object media, String text) { if(failCreate) throw new IllegalStateException("Injected"); return new Body(text); }
    }
    public static class Response {
        Body b; boolean failBuild; boolean removedLength;
        Response(String text) { b=new Body(text); }
        public Body body() { return b; }
        public Body peekBody(long max) { Body copy=new Body(b.text); copy.size=b.size; return copy; }
        public Builder newBuilder() { return new Builder(this); }
    }
    public static class Builder {
        Response source; Body replacement; boolean removed;
        Builder(Response r) { source=r; }
        public Builder body(Object b) { replacement=(Body)b; return this; }
        public Builder removeHeader(String name) { removed=name.equals("Content-Length"); return this; }
        public Response build() {
            if(source.failBuild) throw new IllegalStateException("Injected build failure");
            Response r=new Response(""); r.b=replacement; r.removedLength=removed; return r;
        }
    }
    public static class Model {
        public Object adType;
        public String customNickname="Jump小酱";
        public String adId="present";
        Model(Object type) { adType=type; }
    }
    static void check(boolean ok, String message) { checks++; if(!ok) throw new AssertionError(message); }
    static Method method(Class<?> c, String name, Class<?>... types) throws Exception {
        Method m=c.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    public static void main(String[] args) throws Exception {
        Class<?> views=Class.forName("com.yilink.jumpclean.hooks.ViewCleanHooks");
        Object hook=views.getField("INSTANCE").get(null);
        Method clean=method(views,"cleanRecommendAdConfResponse",ClassLoader.class,Object.class,boolean.class,boolean.class,boolean.class);
        for(int mask=0; mask<8; mask++) {
            boolean promo=(mask&1)!=0, hot=(mask&2)!=0, ad=(mask&4)!=0;
            ConfigManager.INSTANCE.count=0;
            Response source=new Response(JSON);
            Response result=(Response)clean.invoke(hook,LOADER,source,promo,hot,ad);
            JSONObject parsed=new JSONObject(result.b.string());
            JSONArray pages=parsed.getJSONArray("data");
            check(pages.length()==4 && pages.getJSONObject(1).getBoolean("future") && pages.isNull(2) && pages.getInt(3)==42,"Unknown pages preserved");
            JSONArray ads=pages.getJSONObject(0).getJSONArray("ads");
            check(ads.length()==7-Integer.bitCount(mask),"Independent switches");
            check(ads.getString(ads.length()-1).equals("future") && ads.isNull(ads.length()-2),"Unknown ad entries preserved");
            check(ConfigManager.INSTANCE.count==(promo?1:0),"Only promo increments count");
            if(mask!=0) check(source.b.closed && result.removedLength,"Close original only after replacement, clear length");
        }
        for(int mode=0; mode<2; mode++) {
            Response source=new Response(JSON); failCreate=mode==0; source.failBuild=mode==1;
            ConfigManager.INSTANCE.count=0;
            try { clean.invoke(hook,LOADER,source,true,true,true); throw new AssertionError("Expected injected failure"); }
            catch(InvocationTargetException expected) {}
            check(source.b.string().equals(JSON),"Original readable after failure");
            check(ConfigManager.INSTANCE.count==0,"Failed replacement does not count");
        }
        failCreate=false;
        for(String text: List.of("not json","{\"data\":{}}","{\"data\":[]}")) {
            Response source=new Response(text);
            check(clean.invoke(hook,LOADER,source,true,true,true)==source,"Unrecognized/no-op response preserved");
            check(source.b.string().equals(text),"No-op original readable");
        }
        Response large=new Response(JSON); large.b.size=4L*1024*1024+1;
        check(clean.invoke(hook,LOADER,large,true,true,true)==large && !large.b.closed,"Oversize bypass preserves original");
        Class<?> feeds=Class.forName("com.yilink.jumpclean.hooks.FeedHooks");
        Object feed=feeds.getField("INSTANCE").get(null);
        Method isPromo=method(feeds,"isOfficialPromoModel",Object.class);
        for(Object type: new Object[]{null,0,101,106,999,8.5,"0","101"}) {
            check(!(Boolean)isPromo.invoke(feed,new Model(type)),"Nickname/adId/other types must not identify promo");
        }
        for(Object type: new Object[]{8,8L,"8"}) check((Boolean)isPromo.invoke(feed,new Model(type)),"Confirmed promo type");
        Method filter=method(feeds,"filterPromoList",java.util.List.class,boolean.class,boolean.class);
        List<Object> models=new ArrayList<>(List.of(new Model(0),new Model(8)));
        ConfigManager.INSTANCE.count=0;
        check((Boolean)filter.invoke(feed,models,true,false) && models.size()==1,"First batch filtered");
        models.add(new Model(8));
        check((Boolean)filter.invoke(feed,models,true,false) && models.size()==1,"Immediate append to same list filtered");
        check(!(Boolean)filter.invoke(feed,models,true,false) && ConfigManager.INSTANCE.count==2,"Repeat does not count again");
        // Exercise the same production session guard used by the data-entry Hook.
        com.yilink.jumpclean.hooks.AdapterDataPass pass=new com.yilink.jumpclean.hooks.AdapterDataPass();
        Object adapter=new Object();
        var outer=pass.enter(adapter);
        List<Object> batch=new ArrayList<>(List.of(new Model(0),new Model(8)));
        int scans=0;
        for(int i=0;i<5;i++) {
            var nested=pass.enter(adapter);
            try {
                check(nested==outer,"Same adapter joins outer pass");
                if(nested.shouldProcess(batch)) {
                    scans++;
                    filter.invoke(feed,batch,true,false);
                    nested.remember(batch);
                }
            } finally { pass.leave(adapter); }
        }
        check(scans==1 && batch.size()==1,"Five nested entries perform one full filter");
        batch.add(new Model(8));
        check(outer.shouldProcess(batch),"Append within same pass is rechecked");
        filter.invoke(feed,batch,true,false); outer.remember(batch);
        check(!outer.shouldProcess(batch),"Snapshot records post-filter size");
        batch.add(new Model(0)); batch.add(new Model(0)); outer.remember(batch);
        batch.set(1,new Model(8));
        check(outer.shouldProcess(batch),"Same-size middle replacement with unchanged tail is rechecked");
        filter.invoke(feed,batch,true,false); outer.remember(batch);
        Collections.swap(batch,0,1);
        check(outer.shouldProcess(batch),"Reordering is rechecked"); outer.remember(batch);
        check(outer.shouldProcess(new ArrayList<>(batch)),"Different list is not suppressed");
        Object other=new Object(); var otherScope=pass.enter(other);
        check(otherScope.shouldProcess(batch),"Different adapter has separate pass"); pass.leave(other);
        final boolean[] threadChecked={false};
        Thread worker=new Thread(() -> {
            var separate=pass.enter(adapter);
            try { threadChecked[0]=separate.shouldProcess(batch); }
            finally { pass.leave(adapter); }
        });
        worker.start(); worker.join(); check(threadChecked[0],"Different thread has separate pass");
        pass.leave(adapter);
        var next=pass.enter(adapter);
        check(next!=outer && next.shouldProcess(batch),"Next independent update always rechecks, without timer");
        try { throw new IllegalStateException("Host method fails"); }
        catch(IllegalStateException expected) {}
        finally { pass.leave(adapter); }
        var afterFailure=pass.enter(adapter);
        check(afterFailure!=next && afterFailure.shouldProcess(batch),"Exception cleanup releases pass"); pass.leave(adapter);
        var glass=com.yilink.jumpclean.hooks.GlassStyle.INSTANCE;
        var defaults=glass.defaults();
        check(!defaults.getDynamicBg(),"Glass does not redraw continuously by default");
        check(defaults.getDay().getOpacity()==44 && defaults.getNight().getOpacity()==42,"Separate day/night defaults");
        check(defaults.getEdge()==1,"Standard edge highlight by default");
        check(glass.tintColor(defaults.getDay(),false)!=glass.tintColor(defaults.getNight(),true),"Theme-specific glass colors");
        check(glass.accentColor(defaults.getDay(),false)!=glass.accentColor(defaults.getNight(),true),"Theme-specific foreground colors");
        var copy=glass.defaults(); copy.copyFrom(defaults); copy.getDay().setTone(2);
        check(defaults.getDay().getTone()==0 && copy.getNight().getTone()==0,"Deep copy keeps both palettes independent");
        var prefs=ConfigManager.INSTANCE;
        prefs.intPrefs.put("lg_refraction",99); prefs.intPrefs.put("lg_tint_strength",99);
        check(glass.load().getDay().getOpacity()==44,"Legacy optical settings do not override new tuning");
        prefs.intPrefs.put("lg_v4_day_tone",2); prefs.intPrefs.put("lg_v4_day_opacity",24);
        prefs.intPrefs.put("lg_v4_night_tone",1); prefs.intPrefs.put("lg_v4_night_opacity",40);
        prefs.intPrefs.put("lg_v4_day_accent",1); prefs.intPrefs.put("lg_v4_night_accent",2);
        prefs.intPrefs.put("lg_v4_edge",2); prefs.boolPrefs.put("lg_v4_dynamic",true);
        var loaded=glass.load();
        check(loaded.getDay().getTone()==2 && loaded.getDay().getOpacity()==24 && loaded.getDay().getAccent()==1,"Day preferences load independently");
        check(loaded.getNight().getTone()==1 && loaded.getNight().getOpacity()==40 && loaded.getNight().getAccent()==2,"Night preferences load independently");
        check(loaded.getEdge()==2 && loaded.getDynamicBg(),"Edge preset and dynamic background load");
        prefs.intPrefs.put("lg_v4_day_opacity",999); prefs.intPrefs.put("lg_v4_night_opacity",-99);
        prefs.intPrefs.put("lg_v4_day_tone",99); prefs.intPrefs.put("lg_v4_night_accent",-1);
        prefs.intPrefs.put("lg_v4_pill_height",0); prefs.intPrefs.put("lg_v4_pill_margin_h",999);
        loaded=glass.load();
        check(loaded.getDay().getOpacity()==55 && loaded.getNight().getOpacity()==8,"Invalid opacity bounded");
        check(loaded.getDay().getTone()==2 && loaded.getNight().getAccent()==0,"Invalid palette indices bounded");
        check(loaded.getPillHeight()==60 && loaded.getPillMarginH()==40,"Geometry bounded");
        check(glass.edgeHighlightOpacity(0,false) < glass.edgeHighlightOpacity(2,false),"Edge presets differ");
        System.out.println("PASS: " + checks + " assertions (compiled production code; fake host/Xposed)");
    }
}
''',
}
for name, source in sources.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
stdlib = next((CACHE / "org.jetbrains.kotlin/kotlin-stdlib/1.9.24").glob("*/*.jar"))
glass_aar = next((CACHE / "com.github.QWEA0/liquidglass/v2.0.11").glob("*/*.aar"))
glass_jar = OUT / "liquidglass.jar"
with ZipFile(glass_aar) as bundle:
    glass_jar.write_bytes(bundle.read("classes.jar"))
classpath = os.pathsep.join(map(str, [
    OUT, ROOT / "app/build/tmp/kotlin-classes/debug",
    STUDIO / "plugins/grazie/lib/org.json-json.jar", stdlib, glass_jar,
    SDK / "platforms/android-34/android.jar", ROOT / "app/libs/XposedBridgeApi-82.jar",
]))
subprocess.run([str(STUDIO / "jbr/bin/javac"), "-encoding", "UTF-8", "-cp", classpath,
                "-d", str(OUT), *[str(OUT / name) for name in sources]], check=True)
subprocess.run([str(STUDIO / "jbr/bin/java"), "-cp", classpath, "Regression"], check=True)
