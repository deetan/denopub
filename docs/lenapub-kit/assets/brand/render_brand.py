import sys, os
from PIL import Image, ImageDraw, ImageFont
out = sys.argv[1]
BG=(0x0E,0x0F,0x12,255); AMBER=(0xF2,0xB3,0x3D,255); TEXT=(0xED,0xEE,0xF0,255)
SS=4
# Mark geometry in a 100x100 box: capsule (the "l") with a play triangle cut out.
CAP=(28,2,72,98); R=22; TRI=[(40,55),(40,83),(64,69)]
def mark(size, color=AMBER):
    s=size*SS; im=Image.new('RGBA',(s,s),(0,0,0,0)); d=ImageDraw.Draw(im); k=s/100
    d.rounded_rectangle([CAP[0]*k,CAP[1]*k,CAP[2]*k,CAP[3]*k], radius=R*k, fill=color)
    d.polygon([(x*k,y*k) for x,y in TRI], fill=(0,0,0,0))
    return im.resize((size,size), Image.LANCZOS)
def paste_mark(canvas, box_size, cx, cy, color=AMBER):
    m=mark(box_size,color); canvas.alpha_composite(m,(int(cx-box_size/2),int(cy-box_size/2)))
font=lambda px: ImageFont.truetype('/System/Library/Fonts/Avenir Next.ttc', px, index=2)
b=f'{out}/brand'; r=f'{out}/res'
mark(1024).save(f'{b}/lenapub-mark-1024.png')
mark(1024,TEXT).save(f'{b}/lenapub-mark-mono-1024.png')
# launcher icon: full-bleed bg, mark ~55% inside the 66% safe zone
for name,sz in [('ic_launcher-1024',1024),('mipmap-xhdpi/ic_launcher',96),('mipmap-xxhdpi/ic_launcher',144),('mipmap-xxxhdpi/ic_launcher',192)]:
    im=Image.new('RGBA',(sz,sz),BG); paste_mark(im,int(sz*0.62),sz/2,sz/2)
    p=(f'{b}/{name}.png' if name.startswith('ic_') else f'{r}/{name}.png'); os.makedirs(os.path.dirname(p),exist_ok=True); im.save(p)
# adaptive foreground (108dp canvas, content in central 66dp): transparent bg
for d,sz in [('mipmap-xhdpi',216),('mipmap-xxhdpi',324),('mipmap-xxxhdpi',432)]:
    im=Image.new('RGBA',(sz,sz),(0,0,0,0)); paste_mark(im,int(sz*0.5),sz/2,sz/2); im.save(f'{r}/{d}/ic_launcher_foreground.png')
# TV banner 1280x720: mark left, wordmark right
def banner(w,h):
    s=SS; im=Image.new('RGBA',(w*s,h*s),BG)
    # subtle warm vignette on the left
    glow=Image.new('RGBA',(w*s,h*s),(0,0,0,0)); gd=ImageDraw.Draw(glow)
    for i in range(40):
        a=int(10*(1-i/40)); rr=(w*0.55*s)*(i+1)/40
        gd.ellipse([w*0.3*s-rr,h*0.5*s-rr,w*0.3*s+rr,h*0.5*s+rr],outline=None,fill=(0x2a,0x22,0x14,a))
    im.alpha_composite(glow)
    msz=int(h*0.46*s); m=mark(msz//SS).resize((msz,msz),Image.LANCZOS)
    f=font(int(h*0.24*s)); d=ImageDraw.Draw(im); bb=d.textbbox((0,0),'lenapub',font=f)
    tw=bb[2]-bb[0]; mw=int(msz*0.44); gap=int(h*0.06*s)   # visible capsule width is 44% of the box
    x0=int((w*s-(mw+gap+tw))/2); my=int((h*s-msz)/2)
    im.alpha_composite(m,(x0-int(msz*0.28),my))
    ty=int(h*s/2-(bb[1]+bb[3])/2+h*0.03*s)
    d.text((x0+mw+gap-bb[0],ty),'lenapub',font=f,fill=TEXT)
    return im.resize((w,h),Image.LANCZOS)
banner(1280,720).save(f'{b}/banner-1280x720.png')
os.makedirs(f'{r}/drawable-xhdpi',exist_ok=True); os.makedirs(f'{r}/drawable-xxhdpi',exist_ok=True)
banner(1280,720).resize((640,360),Image.LANCZOS).save(f'{r}/drawable-xhdpi/banner.png')
banner(1280,720).resize((960,540),Image.LANCZOS).save(f'{r}/drawable-xxhdpi/banner.png')
# Splash 1920x1080: mark centred, wordmark under it
W,H=1920,1080; s=SS; im=Image.new('RGBA',(W*s,H*s),BG)
ms=320*s; m=mark(320).resize((ms,ms),Image.LANCZOS); im.alpha_composite(m,((W*s-ms)//2,int(300*s)))
d=ImageDraw.Draw(im); f=font(92*s); bb=d.textbbox((0,0),'lenapub',font=f)
d.text(((W*s-(bb[2]-bb[0]))//2-bb[0],int(640*s)),'lenapub',font=f,fill=TEXT)
os.makedirs(f'{r}/drawable-nodpi',exist_ok=True)
im.resize((W,H),Image.LANCZOS).convert('RGB').save(f'{r}/drawable-nodpi/splash_screen.jpg',quality=92)
im.resize((W,H),Image.LANCZOS).convert('RGB').save(f'{b}/splash-1920x1080.jpg',quality=92)
