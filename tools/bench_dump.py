# Usage: python3 tools/bench_dump.py <workdir>
# then: ./gradlew :engine:testDebugUnitTest --tests "*BenchScore*" -Pbench.dir=<workdir>/clips  -> clips/scores.json
# Download maxai bench MP3s, decode to 16k mono, preprocess+fbank (same as batch.py), run the shipped
# int8 ZIPA on desktop ORT, dump per-clip dir the Kotlin BenchScoreTest reads.
import numpy as np, onnxruntime as ort, json, math, os, subprocess, sys, urllib.request, concurrent.futures as cf
S=sys.argv[1]  # work dir: maxai_rows.json in (pron_bench300 rows: id, audio, sentence); clips/ + mp3/ out
OUT=f"{S}/clips"; MP3=f"{S}/mp3"
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rows=json.load(open(f"{S}/maxai_rows.json"))
tbl=json.load(open(f"{ROOT}/table/sentence_ipa.json")); low={k.lower():k for k in tbl}
def preprocess(x):
    xd=x.astype(np.float64); peak=max(1e-9,float(np.abs(xd).max())); g=0.9/peak if peak<0.9 else 1.0
    hop=160; first=last=-1
    for s in range(0,len(xd)-hop+1,hop):
        seg=xd[s:s+hop]*g
        if math.sqrt(float((seg*seg).sum())/hop)>0.02:
            if first<0: first=s
            last=s+hop
    if first<0: return (xd*g).astype(np.float32)
    lo=max(0,first-4000); hi=min(len(xd),last+4000); return (xd[lo:hi]*g).astype(np.float32)
SR,FL,FS,NFFT,PRE,BINS=16000,400,160,512,0.97,80
win=(0.5-0.5*np.cos(2*np.pi*np.arange(FL)/(FL-1)))**0.85
mel=lambda f:1127.0*np.log(1.0+f/700.0); lo_,hi_=mel(20.0),mel(SR/2-400.0); pts=lo_+(hi_-lo_)*np.arange(BINS+2)/(BINS+1)
nb=NFFT//2+1; banks=np.zeros((BINS,nb))
for k in range(nb):
    fm=mel(k*SR/NFFT)
    for b in range(BINS):
        l,c,r=pts[b],pts[b+1],pts[b+2]; v=min((fm-l)/(c-l),(r-fm)/(r-c))
        if v>0: banks[b,k]=v
def fbank(y):
    yd=y.astype(np.float64); n=len(yd); T=(n+FS//2)//FS; feats=np.zeros((T,BINS),np.float32)
    for t in range(T):
        s=t*FS+FS//2-FL//2; idx=np.arange(s,s+FL); idx=np.where(idx<0,-idx-1,idx); idx=np.where(idx>=n,2*n-idx-1,idx)
        fr=yd[idx].copy(); fr-=fr.mean(); fr2=fr.copy(); fr2[1:]=(fr[1:]-PRE*fr[:-1])*win[1:]; fr2[0]=fr[0]*(1-PRE)*win[0]
        spec=np.abs(np.fft.rfft(fr2,NFFT))**2; feats[t]=np.log(np.maximum(banks@spec,1.1920928955078125e-07))
    return feats
def fetch(r):
    p=f"{MP3}/{r['id']}.mp3"
    if not os.path.exists(p): urllib.request.urlretrieve(r["audio"],p)
    return p
with cf.ThreadPoolExecutor(8) as ex: list(ex.map(fetch,rows))
print("downloaded",len(os.listdir(MP3)),flush=True)
so=ort.SessionOptions(); so.intra_op_num_threads=4
sess=ort.InferenceSession(f"{ROOT}/engine/src/main/assets/pronunciation_engine/zipa/model.int8.onnx",so,providers=["CPUExecutionProvider"])
done=[];skipped=[]
for r in rows:
    key=low.get(r["sentence"].lower())
    if not key: skipped.append((r["id"],"not in table")); continue
    pcm=subprocess.run(["ffmpeg","-v","error","-i",f"{MP3}/{r['id']}.mp3","-ac","1","-ar","16000","-f","f32le","-"],capture_output=True).stdout
    x=np.frombuffer(pcm,dtype="<f4"); y=preprocess(x)
    if len(y)<8000: skipped.append((r["id"],f"short {len(y)/16000:.2f}s")); continue
    f=fbank(y); d=f"{OUT}/{r['id']}"; os.makedirs(d,exist_ok=True)
    lp=sess.run(["log_probs"],{"x":f[None],"x_lens":np.array([len(f)],np.int64)})[0][0]
    y.astype("<f4").tofile(f"{d}/samples.bin"); lp.astype("<f4").tofile(f"{d}/lp_int8.bin")
    json.dump({"id":r["id"],"sentence":key,"table":tbl[key],"T":int(lp.shape[0]),"V":int(lp.shape[1]),"rawDur":len(x)/16000},open(f"{d}/meta.json","w"),ensure_ascii=False)
    done.append(r["id"])
    if len(done)%50==0: print(len(done),"clips…",flush=True)
print("done",len(done),"skipped",skipped)
