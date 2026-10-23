package io.gpsz.zygote;
import android.os.SystemClock;
public class BeatTest {
  static int fails=0;
  static void eq(String n, Object a, Object b){ if(!a.equals(b)){fails++; System.out.println("FAIL "+n+": "+a+" != "+b);} else System.out.println("ok   "+n); }
  static void sput(String k,String v){ android.provider.Settings.Global.store.put(Config.tag(k),Config.enc(v)); }
  static String sget(String k){ return Config.dec(android.provider.Settings.Global.store.get(Config.tag(k))); }
  static void poll(long t,String cfg,String beat){ SystemClock.fake=t; Config.parse(cfg); Config.checkBeat(beat); }
  public static void main(String[] x){
    for(String[] w:new String[][]{{"WIRE_PLAIN","WIRE_HEX"},{"WIRE_PLAIN5","WIRE_HEX5"}}){ String pl=System.getenv(w[0]), hx=System.getenv(w[1]); if(pl==null||hx==null||pl.isEmpty()||hx.isEmpty()){ fails++; System.out.println("FAIL interop: "+w[0]+"/"+w[1]+" tidak diisi dari .salt"); } else eq("interop: nilai beat daemon terbaca Config.dec ("+pl+")",Config.dec(hx),pl); }
    String PL="enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;spd=33.0;alt=1;altv=0;rr=8;brg=30;pl=1";
    String SP="enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;spd=0;alt=1;altv=0;rr=8";
    long t=1000;
    for(int i=0;i<20;i++){ poll(t,PL,""+(i/4)); t+=250; }
    eq("normal enabled",Config.enabled,true); eq("normal spd",Config.spd,33.0f);
    for(int up=36000;up<36030;up++) for(int q=0;q<4;q++){ poll(t,PL,Config.dec(Config.enc(""+up))); t+=250; }
    eq("uptime 5 digit: beat berganti tiap detik tidak dianggap basi",Config.spd,33.0f);
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
    Config.Pt a=Config.snapshot(); SystemClock.fake+=30; Config.Pt b=Config.snapshot();
    eq("snapshot tanpa jitter lat dalam 1 m",Math.abs(a.lat+7.7)<1e-5,true); eq("snapshot tanpa jitter lng dalam 1 m",Math.abs(a.lng-110.4)<1e-5,true);
    eq("snapshot < 50 ms dipakai ulang",a==b,true);
    SystemClock.fake+=40; eq("snapshot >= 50 ms diperbarui",Config.snapshot()!=a,true);
    SystemClock.fake+=1000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8");
    boolean inside=true; for(int i=0;i<200;i++){ SystemClock.fake+=250; Config.Pt p=Config.snapshot();
      if(Math.abs(p.lat+7.7)*111320.0>8.001 || Math.abs(p.lng-110.4)*111320.0*Math.cos(Math.toRadians(-7.7))>8.001) inside=false; }
    eq("snapshot jitter dalam radius rr",inside,true);
    SystemClock.fake+=1000; Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=1;rr=8");
    double maxStep=0; Config.Pt prev=null; boolean in2=true;
    for(int i=0;i<300;i++){ SystemClock.fake+=1000; Config.Pt p=Config.snapshot();
      double dn=(p.lat+7.7)*111320.0, de=(p.lng-110.4)*111320.0*Math.cos(Math.toRadians(-7.7));
      if(Math.hypot(dn,de)>8.001) in2=false;
      if(prev!=null){ double st=Math.hypot((p.lat-prev.lat)*111320.0,(p.lng-prev.lng)*111320.0*Math.cos(Math.toRadians(-7.7))); if(st>maxStep) maxStep=st; }
      prev=p; }
    eq("jitter: lingkaran (bukan persegi) radius rr",in2,true);
    eq("jitter: loncatan antar fix 1 dtk <= 8 m (dulu bisa ~22 m)",maxStep<=8.0,true);
    Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=0;random=0;rr=8");
    eq("accNow: akurasi 0 -> 1",Config.accNow(null),1f);
    Config.parse("enabled=1;lat=-7.7;lng=110.4;acc=10;random=0;rr=8;spd=3");
    Config.parse("enabled=1;lat=200;lng=999;acc=NaN;spd=Infinity;alt=NaN;altv=-5;rr=NaN");
    eq("input tidak valid: lat/lng di luar rentang diabaikan",Config.pos.lat==-7.7&&Config.pos.lng==110.4,true);
    eq("input tidak valid: acc NaN diabaikan",Config.accNow(null)==10f,true);
    { String ck=Config.KEY+","+Config.KEY_SYS+","+Config.KEY_BEAT+","+Config.KEY_TRK+","+Config.KEY_NET+","+Config.KEY_BT+",";
      eq("kunci Config sama dengan daemon",ck,System.getenv("DAEMON_KEYS")); }
    { String base=";acc=10;random=0";
      Config.parse("enabled=1;lat=-7.7;lng=110.4"+base);
      Config.parse("enabled=1;lat=90.5;lng=180.5"+base);
      eq("batas: lat 90,5 dan lng 180,5 ditolak",Config.pos.lat==-7.7&&Config.pos.lng==110.4,true);
      Config.parse("enabled=1;lat=90;lng=180"+base);
      eq("batas: lat 90 dan lng 180 diterima",Config.pos.lat==90.0&&Config.pos.lng==180.0,true);
      Config.parse("enabled=1;lat=-90;lng=-180"+base);
      eq("batas: lat -90 dan lng -180 diterima",Config.pos.lat==-90.0&&Config.pos.lng==-180.0,true);
      Config.parse("enabled=1;lat=-7.7;lng=110.4;brg=-5"+base);
      eq("bearing negatif -> tidak ada bearing",Float.isNaN(Config.brg),true);
      Config.parse("enabled=1;lat=-7.7;lng=110.4;brg=370"+base);
      eq("bearing 370 -> 10",Config.brg,10f);
      Config.parse("enabled=1;lat=-7.7;lng=110.4"+base); }
    eq("input tidak valid: spd Infinity -> 0",Config.spd,0f);
    eq("input tidak valid: alt NaN -> 0",Config.alt,0.0);
    eq("input tidak valid: altv negatif -> 0",Config.altVar,0f);
    eq("input tidak valid: rr NaN -> 8",Config.rr,8f);
    java.util.Map<String,String> st=android.provider.Settings.Global.store; int[] ran={0}; boolean[] hooked={false}; java.util.function.BooleanSupplier ready=()->{ ran[0]++; return hooked[0]; }; st.put("boot_count","7");
    SystemClock.fake+=10000; sput("cfg",PL); sput("beat","a"); st.remove(Config.tag("sys"));
    Config.refresh(null,null,ready);
    eq("refresh: cfg terbaca",Config.enabled,true); eq("refresh: sys off",Config.system,false); eq("refresh: onReady belum jalan",ran[0],0);
    sput("sys","1"); Config.refresh(null,null,ready);
    eq("refresh: penanda baca dibersihkan",Config.READING.get()==null,true);
    eq("refresh: sys on",Config.system,true); eq("refresh: heartbeat off",Config.sysHeartbeat,false); eq("refresh: onReady jalan",ran[0],1);
    eq("refresh: hook gagal -> live tidak dipublikasikan",st.containsKey(Config.tag("live")),false);
    sput("sys","2"); Config.refresh(null,null,ready); Config.refresh(null,null,ready);
    eq("refresh: heartbeat on",Config.sysHeartbeat,true); eq("refresh: percobaan ulang ditahan jeda",ran[0],1);
    SystemClock.fake+=6000; Config.refresh(null,null,ready); Config.refresh(null,null,ready);
    eq("refresh: hook masih gagal -> coba ulang sesudah jeda",ran[0],2); eq("refresh: tetap tidak dipublikasikan",st.containsKey(Config.tag("live")),false);
    hooked[0]=true; android.app.Application app=new android.app.Application(); st.put("boot_count","7");
    SystemClock.fake+=6000; Config.refresh(null,app,ready); Config.refresh(null,app,ready);
    eq("refresh: hook sukses -> onReady jalan",ran[0],3); eq("refresh: hook sukses -> live = boot_count","7".equals(sget("live")),true);
    st.put("boot_count","8"); SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: boot_count naik belakangan -> live menyusul","8".equals(sget("live")),true);
    hooked[0]=false; SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: hook hilang -> live dicabut","".equals(sget("live")),true);
    hooked[0]=true; SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: hook pulih -> live terbit lagi","8".equals(sget("live")),true);
    hooked[0]=false; sput("sys","1"); Config.refresh(null,app,ready);
    eq("refresh: heartbeat -> mode penuh tanpa hook LMS -> live dicabut tanpa jeda","".equals(sget("live")),true);
    hooked[0]=true; sput("sys","2"); SystemClock.fake+=6000; Config.refresh(null,app,ready);
    eq("refresh: kembali ke heartbeat -> live terbit lagi","8".equals(sget("live")),true);
    sput("cfg","enabled=0;lat=-7.7;lng=110.4;acc=10;random=0"); Config.refresh(null,null,ready);
    eq("refresh: perubahan cfg langsung",Config.enabled,false);
    sput("cfg",PL); Config.refresh(null,null,null); for(int i=0;i<12;i++){ SystemClock.fake+=1000; Config.refresh(null,null,null); }
    eq("refresh: beat basi 12 dtk -> freeze",Config.spd,0f);
    for(int i=0;i<60;i++){ SystemClock.fake+=1000; Config.refresh(null,null,null); }
    eq("refresh: beat basi 72 dtk -> spoof off",Config.enabled,false);
    sput("beat","resume1"); SystemClock.fake+=1000; Config.refresh(null,null,null);
    eq("refresh: beat kembali -> enabled langsung di refresh yang sama",Config.enabled,true); eq("refresh: beat kembali -> spd langsung pulih",Config.spd,33.0f);
    SystemClock.fake=2_000_000; double[] pv=Config.networkOffset(-7.7); long lc=-1, mn=Long.MAX_VALUE, mx=0, sm=0; int cn=0;
    for(long tt=2_000_000;tt<2_000_000+900_000L;tt+=50){ SystemClock.fake=tt; double[] o=Config.networkOffset(-7.7); if(o[0]!=pv[0]||o[1]!=pv[1]){ if(lc>=0){ long d=tt-lc; mn=Math.min(mn,d); mx=Math.max(mx,d); sm+=d; cn++; } lc=tt; pv=o; } }
    eq("networkOffset: interval ganti acak 20-40 dtk",cn>=10&&mn>=19950&&mx<=40100&&sm/cn>=24000,true);
    { final String CA="enabled=1;lat=-7.0;lng=110.0;acc=10;random=0", CB="enabled=1;lat=40.0;lng=-74.0;acc=10;random=0";
      final java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean(false);
      Thread w=new Thread(()->{ boolean f=false; while(!stop.get()){ Config.parse(f?CA:CB); f=!f; } });
      w.start(); try{ Thread.sleep(30); }catch(InterruptedException e){}
      long end=System.nanoTime()+1_500_000_000L, clk=9_000_000; int torn=0, reads=0;
      while(System.nanoTime()<end){ clk+=100; SystemClock.fake=clk; Config.Pt p=Config.snapshot(); reads++;
        boolean pa=Math.abs(p.lat+7.0)<1e-4&&Math.abs(p.lng-110.0)<1e-4, pb=Math.abs(p.lat-40.0)<1e-4&&Math.abs(p.lng+74.0)<1e-4; if(!pa&&!pb) torn++; }
      stop.set(true); try{ w.join(); }catch(InterruptedException e){}
      eq("snapshot: lat/lng selalu satu pasangan utuh ("+reads+" baca)",torn,0); }
    System.out.println(fails==0?"ALL PASS":"FAILURES "+fails); System.exit(fails);
  }
}
