package com.blanoir.tempos.client.ui.hud;

import com.blanoir.tempos.client.ui.paint.UiBatch;
import com.blanoir.tempos.client.ui.paint.UiPainter;
import com.blanoir.tempos.client.ui.paint.UiTexture;
import com.blanoir.tempos.client.ui.theme.Theme;
import java.nio.ByteBuffer;

/** Pure recording/cache checks; no GPU or game rendering is simulated as pixel verification. */
public final class HudRasterCacheVerification {
    private static final Object SURFACE = new Object(), WORLD = new Object();
    private static final class Texture implements UiTexture {
        long version;
        @Override public long revision() { return version; }
        @Override public void update(int x,int y,int w,int h,ByteBuffer pixels) { version++; }
        @Override public void close() {}
    }
    private static final UiTexture EXTERNAL = new UiTexture() {
        public void update(int x,int y,int w,int h,ByteBuffer pixels) {}
        public void close() {}
    };
    private static void yes(boolean value,String label) {
        if (!value) throw new AssertionError(label);
    }
    private static UiBatch record(HudRasterCache cache,UiTexture texture,int color,int clip,float x) {
        UiPainter p = cache.painter();
        p.begin(400,200,1);
        p.pushClip(0,0,clip,200);
        p.fill(x,12,30,20,2,color);
        p.line(7,9,23,26,.8f,color);
        if (texture!=null) p.image(texture,50,15,16,16,0,0,1,1,-1);
        p.popClip();
        return p.end();
    }
    private static boolean frame(HudRasterCache cache,UiTexture texture,int color,int clip,float x) {
        UiBatch batch = record(cache,texture,color,clip,x);
        boolean reuse = cache.canReuse(batch,SURFACE,WORLD);
        cache.remember(batch,SURFACE,WORLD);
        return reuse;
    }
    public static void main(String[] args) {
        Texture atlas = new Texture();
        HudRasterCache cache = new HudRasterCache();
        yes(!frame(cache,atlas,-1,400,12),"initial draw");
        yes(frame(cache,atlas,-1,400,12),"static reuse");
        yes(!frame(cache,atlas,0xff112233,400,12),"color/quantity glyph bytes changed");
        yes(frame(cache,atlas,0xff112233,400,12),"reuse changed content once stable");
        yes(!frame(cache,atlas,0xff112233,399,12),"clip changed without vertex changes");
        yes(!frame(cache,atlas,0xff112233,399,12.25f),"drag or animation changed position");
        atlas.update(0,0,1,1,null);
        yes(!frame(cache,atlas,0xff112233,399,12.25f),"in-place UI atlas update invalidates");
        yes(frame(cache,atlas,0xff112233,399,12.25f),"updated atlas becomes reusable");
        yes(!frame(cache,new Texture(),0xff112233,399,12.25f),"different texture identity");
        yes(!frame(cache,EXTERNAL,-1,400,12),"external skin drawn");
        yes(!frame(cache,EXTERNAL,-1,400,12),"unchanged borrowed view still redraws");
        cache.reset();
        UiBatch batch = record(cache,atlas,-1,400,12);
        cache.remember(batch,SURFACE,WORLD);
        batch = record(cache,atlas,-1,400,12);
        yes(!cache.canReuse(batch,new Object(),WORLD),"new attachment");
        yes(!cache.canReuse(batch,SURFACE,new Object()),"new world");
        cache.painter().begin(401,201,1);
        cache.painter().fill(12,12,30,20,2,-1);
        yes(!cache.canReuse(cache.painter().end(),SURFACE,WORLD),"resolution change");
        cache.painter().begin(400,200,2);
        cache.painter().fill(6,6,15,10,1,-1);
        yes(!cache.canReuse(cache.painter().end(),SURFACE,WORLD),"GUI scale change");
        cache.painter().begin(400,200,1);
        yes(!cache.canReuse(cache.painter().end(),SURFACE,WORLD),"empty batch never presents old raster");
        cache.reset();
        yes(!frame(cache,atlas,-1,400,12),"reappear after empty or world teardown redraws");
        Theme rgb = new Theme(); rgb.rgb(true);
        for (int i=0;i<2;i++) {
            UiPainter painter = cache.painter(); painter.begin(400,200,1,rgb);
            painter.accentFill(12,12,30,20,2,255,1);
            batch=painter.end();
            yes(!cache.canReuse(batch,SURFACE,WORLD),"RGB uniform forbids raster reuse");
            cache.remember(batch,SURFACE,WORLD);
        }
        cache.reset();
        Texture second = new Texture();
        for (int i=0;i<2;i++) {
            UiPainter p=cache.painter(); p.begin(400,200,1);
            p.image(i==0?atlas:second,10,10,16,16,0,0,1,1,-1);
            p.image(i==0?second:atlas,10,10,16,16,0,0,1,1,-1);
            batch=p.end();
            yes(!cache.canReuse(batch,SURFACE,WORLD),"range texture order must match");
            cache.remember(batch,SURFACE,WORLD);
        }
        cache.reset();
        ByteBuffer[] buffers = new ByteBuffer[2];
        int reused=0;
        for (int i=0;i<10_000;i++) {
            batch=record(cache,atlas,-1,400,12);
            if (i<2) buffers[i]=batch.vertices();
            else yes(buffers[i%2]==batch.vertices(),"only two stable recording buffers");
            if(cache.canReuse(batch,SURFACE,WORLD)) reused++;
            cache.remember(batch,SURFACE,WORLD);
            batch.vertices().position(batch.vertices().limit());
        }
        yes(reused==9_999,"all repeated static frames reuse despite uploader buffer position");
        System.out.println("HUD_RASTER_CACHE_PASS byte equality, order, clip, atlas updates, external textures, RGB, surface/world/size, empty transition, 10000 frames using two buffers");
    }
}
