import zipfile,struct,hashlib,sys,io,datetime
sys.path.insert(0,'.')
from axml_strip import strip
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,padding
from cryptography import x509
from cryptography.x509.oid import NameOID
import os
SRC=sys.argv[1] if len(sys.argv)>1 else 'SetLoc-zygisk-patched.apk'
OUT=sys.argv[2] if len(sys.argv)>2 else 'SetLoc-noxposed.apk'
# ---- key (dipakai ulang kalau release-key.pem + release-cert.pem ada, supaya update APK tetap satu tanda tangan)
if os.path.exists('release-key.pem') and os.path.exists('release-cert.pem'):
    key=serialization.load_pem_private_key(open('release-key.pem','rb').read(),None)
    cert=x509.load_pem_x509_certificate(open('release-cert.pem','rb').read())
else:
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'SetLoc Zygisk')])
    now=datetime.datetime(2026,10,12)
    cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
     .serial_number(x509.random_serial_number()).not_valid_before(now).not_valid_after(now+datetime.timedelta(days=365*30))
     .sign(key,hashes.SHA256()))
    open('release-key.pem','wb').write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    open('release-cert.pem','wb').write(cert.public_bytes(serialization.Encoding.PEM))
cert_der=cert.public_bytes(serialization.Encoding.DER)
# ---- repack zip
src=zipfile.ZipFile(SRC)
buf=io.BytesIO()
dst=zipfile.ZipFile(buf,'w')
drop=lambda n: n in('assets/xposed_init','META-INF/MANIFEST.MF') or (n.startswith('META-INF/') and n.endswith(('.SF','.RSA','.DSA','.EC')))
for zi in src.infolist():
    n=zi.filename
    if drop(n): continue
    data=src.read(n)
    if n=='AndroidManifest.xml':
        data,rem=strip(data); assert len(rem)==4,rem
    ni=zipfile.ZipInfo(n,date_time=(2026,10,12,0,0,0))
    ni.external_attr=zi.external_attr
    ni.compress_type=zi.compress_type
    if n=='resources.arsc': ni.compress_type=zipfile.ZIP_STORED
    if ni.compress_type==zipfile.ZIP_STORED and not n.endswith('/'):
        off=buf.tell()+30+len(n.encode())
        pad=(4-off%4)%4
        ni.extra=b'\0'*pad
    dst.writestr(ni,data)
dst.close()
z=bytearray(buf.getvalue())
# ---- locate EOCD
eo=z.rfind(b'PK\x05\x06'); cdsize,cdoff=struct.unpack_from('<II',z,eo+12)
assert cdoff+cdsize==eo
def chunks(b):
    for i in range(0,len(b),1<<20): yield b[i:i+(1<<20)]
sec1=bytes(z[:cdoff]); sec3=bytes(z[cdoff:cdoff+cdsize]); sec4=bytes(z[eo:])  # EOCD has cdoff = start of block (no block yet)
def digest(s1,s2,s3):
    ds=[]
    for part in (s1,s2,s3):
        for c in chunks(part): ds.append(hashlib.sha256(b'\xa5'+struct.pack('<I',len(c))+c).digest())
    return hashlib.sha256(b'\x5a'+struct.pack('<I',len(ds))+b''.join(ds)).digest()
dg=digest(sec1,sec3,sec4)
lp=lambda b: struct.pack('<I',len(b))+b
algo=0x0103
digests=lp(struct.pack('<I',algo)+lp(dg))
signed=lp(digests)+lp(lp(cert_der))+lp(b'')
sig=key.sign(signed,padding.PKCS1v15(),hashes.SHA256())
pub=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
signer=lp(signed)+lp(lp(struct.pack('<I',algo)+lp(sig)))+lp(pub)
value=lp(lp(signer))
pair=struct.pack('<Q',4+len(value))+struct.pack('<I',0x7109871a)+value
block=struct.pack('<Q',len(pair)+24)+pair+struct.pack('<Q',len(pair)+24)+b'APK Sig Block 42'
newoff=cdoff+len(block)
eocd=bytearray(sec4); struct.pack_into('<I',eocd,16,newoff)
final=bytes(z[:cdoff])+block+sec3+bytes(eocd)
open(OUT,'wb').write(final)
print('ok',OUT,len(final))
