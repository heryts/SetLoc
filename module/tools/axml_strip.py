import struct
TARGET={'xposedmodule','xposeddescription','xposedminversion','xposedsharedprefs','xposedscope'}
def strip(data):
    assert struct.unpack_from('<H',data,0)[0]==3
    pos=8; out=bytearray(data[:8]); strings=None; removed=[]
    # string pool
    t,hs,sz=struct.unpack_from('<HHI',data,pos)
    assert t==1
    sp=data[pos:pos+sz]
    scount,stylecount,flags,sstart,ststart=struct.unpack_from('<IIIII',sp,8)
    offs=struct.unpack_from('<%dI'%scount,sp,28)
    utf8=bool(flags&0x100)
    def getstr(i):
        o=sstart+offs[i]
        if utf8:
            l=sp[o]; o+=1 if not l&0x80 else 2
            if sp[o-1 if False else o-1]&0x80 and False: pass
            # utf8: u16len(1-2 bytes) then u8len(1-2 bytes)
            return None
        l=struct.unpack_from('<H',sp,o)[0]; return sp[o+2:o+2+l*2].decode('utf-16le')
    def getstr8(i):
        o=sstart+offs[i]
        b=sp[o]; o+=2 if b&0x80 else 1       # char length
        b=sp[o]; 
        if b&0x80: l=((b&0x7f)<<8)|sp[o+1]; o+=2
        else: l=b; o+=1
        return sp[o:o+l].decode('utf-8','replace')
    S=getstr8 if utf8 else getstr
    out+=data[pos:pos+sz]; pos+=sz
    n=len(data); skip_until_end=None
    while pos<n:
        t,hs,sz=struct.unpack_from('<HHI',data,pos)
        chunk=data[pos:pos+sz]
        if t==0x102:
            ns,name,as_,asz,ac=struct.unpack_from('<iiHHH',chunk,16)
            if S(name)=='meta-data':
                nm=None
                for k in range(ac):
                    ao=16+as_+k*20
                    ans,aname,raw,vsz,r0,vt,vd=struct.unpack_from('<iiiHBBi',chunk,ao)
                    if S(aname)=='name' and raw>=0: nm=S(raw)
                if nm in TARGET:
                    removed.append(nm); skip_until_end=True
                    pos+=sz; continue
        if t==0x103 and skip_until_end:
            skip_until_end=None; pos+=sz; continue
        out+=chunk; pos+=sz
    struct.pack_into('<I',out,4,len(out))
    return bytes(out),removed
def list_meta(data):
    pos=8
    t,hs,sz=struct.unpack_from('<HHI',data,pos); sp=data[pos:pos+sz]
    scount,_,flags,sstart,_=struct.unpack_from('<IIIII',sp,8); offs=struct.unpack_from('<%dI'%scount,sp,28); utf8=bool(flags&0x100)
    def S(i):
        o=sstart+offs[i]
        if utf8:
            b=sp[o]; o+=2 if b&0x80 else 1
            b=sp[o]
            if b&0x80: l=((b&0x7f)<<8)|sp[o+1]; o+=2
            else: l=b; o+=1
            return sp[o:o+l].decode('utf-8','replace')
        l=struct.unpack_from('<H',sp,o)[0]; return sp[o+2:o+2+l*2].decode('utf-16le')
    pos+=sz; names=[]; depth=0; starts=ends=0
    while pos<len(data):
        t,hs,sz=struct.unpack_from('<HHI',data,pos)
        if t==0x102:
            starts+=1
            ns,name,as_,asz,ac=struct.unpack_from('<iiHHH',data,pos+16)
            if S(name)=='meta-data':
                for k in range(ac):
                    ao=pos+16+as_+k*20
                    ans,aname,raw,*_=struct.unpack_from('<iii',data,ao)
                    if S(aname)=='name' and raw>=0: names.append(S(raw))
        if t==0x103: ends+=1
        pos+=sz
    return names,starts,ends
