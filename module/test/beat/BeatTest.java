package io.gpsz.zygote;
import android.os.SystemClock;
public class BeatTest {
  static int fails=0;
  static void eq(String n, Object a, Object b){ if(!a.equals(b)){fails++; System.out.println("FAIL "+n+": "+a+" != "+b);} else System.out.println("ok   "+n); }
  static void poll(long t,String cfg,String beat){ SystemClock.fake=t; Config.parse(cfg); Config.checkBeat(beat); }
  public static void main(String[] x){
    String PL="enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;spd=33.0;alt=1;altv=0;rr=8;brg=30;pl=1";
    String SP="enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;spd=0;alt=1;altv=0;rr=8";
    long t=1000;
    for(int i=0;i<20;i++){ poll(t,PL,""+(i/4)); t+=250; }
    eq("normal enabled",Config.enabled,true); eq("normal spd",Config.spd,33.0f);
    String last="4"; long t0=t;
    for(;t<t0+9000;t+=250) poll(t,PL,last);
    eq("9s stale: still moving",Config.spd,33.0f);
    for(;t<t0+11000;t+=250) poll(t,PL,last);
    eq("11s stale: frozen spd=0",Config.spd,0f); eq("11s stale: still enabled",Config.enabled,true);
    for(;t<t0+59000;t+=250) poll(t,PL,last);
    eq("59s: enabled",Config.enabled,true);
    for(;t<t0+61000;t+=250) poll(t,PL,last);
    eq("61s: spoof off",Config.enabled,false);
    poll(t,PL,"900"); t+=250; poll(t,PL,"900");
    eq("resume enabled",Config.enabled,true); eq("resume spd",Config.spd,33.0f);
    t0=t; for(;t<t0+3000;t+=250) poll(t,PL,"900");
    t+=30000; poll(t,PL,"900");
    eq("after gap: not frozen",Config.spd,33.0f); eq("after gap: enabled",Config.enabled,true);
    t0=t; for(;t<t0+120000;t+=250) poll(t,SP,"1");
    eq("single point 120s: enabled",Config.enabled,true);
    t+=250; poll(t,PL,"1"); eq("first pl poll enabled",Config.enabled,true); eq("first pl poll spd",Config.spd,33.0f);
    t+=250; poll(t,"enabled=0;lat=-7.7;lng=110.4;acc=10;random=0","1"); eq("stop",Config.enabled,false);
    t0=t; Config.parse(PL); for(;t<t0+12000;t+=250) poll(t,PL,null);
    eq("null beat 12s -> frozen",Config.spd,0f);
    SystemClock.fake=500000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;rr=8");
    Config.Pt a=Config.snapshot(); SystemClock.fake+=150; Config.Pt b=Config.snapshot();
    eq("snapshot tanpa jitter lat",a.lat,-7.7); eq("snapshot tanpa jitter lng",a.lng,110.4);
    eq("snapshot < 200 ms dipakai ulang",a==b,true);
    SystemClock.fake+=100; eq("snapshot >= 200 ms diperbarui",Config.snapshot()!=a,true);
    SystemClock.fake+=1000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8");
    boolean inside=true; for(int i=0;i<200;i++){ SystemClock.fake+=250; Config.Pt p=Config.snapshot();
      if(Math.abs(p.lat+7.7)*111320.0>8.001 || Math.abs(p.lng-110.4)*111320.0*Math.cos(Math.toRadians(-7.7))>8.001) inside=false; }
    eq("snapshot jitter dalam radius rr",inside,true);
    java.util.Map<String,String> st=android.provider.Settings.Global.store; int[] ran={0}; boolean[] hooked={false}; java.util.function.BooleanSupplier ready=()->{ ran[0]++; return hooked[0]; }; st.put("boot_count","7");
    SystemClock.fake+=10000; st.put("gpsz_cfg",PL); st.put("gpsz_beat","a"); st.remove("gpsz_sys");
    Config.refresh(null,null,ready);
    eq("refresh: cfg terbaca",Config.enabled,true); eq("refresh: sys off",Config.system,false); eq("refresh: onReady belum jalan",ran[0],0);
    st.put("gpsz_sys","1"); Config.refresh(null,null,ready);
    eq("refresh: sys on",Config.system,true); eq("refresh: heartbeat off",Config.sysHeartbeat,false); eq("refresh: onReady jalan",ran[0],1);
    eq("refresh: hook gagal -> live tidak dipublikasikan",st.containsKey("gpsz_sys_live"),false);
    st.put("gpsz_sys","2"); Config.refresh(null,null,ready); Config.refresh(null,null,ready);
    eq("refresh: heartbeat on",Config.sysHeartbeat,true); eq("refresh: percobaan ulang ditahan jeda",ran[0],1);
    SystemClock.fake+=6000; Config.refresh(null,null,ready); Config.refresh(null,null,ready);
    eq("refresh: hook masih gagal -> coba ulang sesudah jeda",ran[0],2); eq("refresh: tetap tidak dipublikasikan",st.containsKey("gpsz_sys_live"),false);
    hooked[0]=true; android.app.Application app=new android.app.Application(); st.put("boot_count","7");
    SystemClock.fake+=6000; Config.refresh(null,app,ready); Config.refresh(null,app,ready);
    eq("refresh: hook sukses -> onReady jalan",ran[0],3); eq("refresh: hook sukses -> live = boot_count","7".equals(st.get("gpsz_sys_live")),true);
    st.put("boot_count","8"); SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: boot_count naik belakangan -> live menyusul","8".equals(st.get("gpsz_sys_live")),true);
    hooked[0]=false; SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: hook hilang -> live dicabut","".equals(st.get("gpsz_sys_live")),true);
    hooked[0]=true; SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: hook pulih -> live terbit lagi","8".equals(st.get("gpsz_sys_live")),true);
    hooked[0]=false; st.put("gpsz_sys","1"); Config.refresh(null,app,ready);
    eq("refresh: heartbeat -> mode penuh tanpa hook LMS -> live dicabut tanpa jeda","".equals(st.get("gpsz_sys_live")),true);
    hooked[0]=true; st.put("gpsz_sys","2"); SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: kembali ke heartbeat -> live terbit lagi","8".equals(st.get("gpsz_sys_live")),true);
    st.put("gpsz_cfg","enabled=0;lat=-7.7;lng=110.4;acc=10;random=0"); Config.refresh(null,null,ready);
    eq("refresh: perubahan cfg langsung",Config.enabled,false);
    st.put("gpsz_cfg",PL); Config.refresh(null,null,null); for(int i=0;i<12;i++){ SystemClock.fake+=1000; Config.refresh(null,null,null); }
    eq("refresh: beat basi 12 dtk -> freeze",Config.spd,0f);
    System.out.println(fails==0?"ALL PASS":"FAILURES "+fails); System.exit(fails);
  }
}
