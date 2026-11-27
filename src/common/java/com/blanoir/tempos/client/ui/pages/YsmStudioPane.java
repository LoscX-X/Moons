package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.api.YsmStudio;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import java.util.*;
import java.util.function.*;

/** Retains controls while the optional model publishes animation time and parameter changes. */
final class YsmStudioPane extends Widget {
    private static final YsmStudio.BonePose REST = new YsmStudio.BonePose(0,0,0,0,0,0,1,1,1,false);
    private static final String[] AXES = {"Position X","Position Y","Position Z","Rotation X","Rotation Y","Rotation Z","Scale X","Scale Y","Scale Z"};
    final TextField search = new TextField("Search",this::filter,true);
    private final TextField expression = new TextField("Local Molang expression",value->{});
    private final Scroll list = new Scroll(false);
    private final YsmStudioRow status = new YsmStudioRow("","",Theme.TEXT,null,null,true);
    private final ArrayList<Widget> rows = new ArrayList<>();
    private final ArrayList<Runnable> updates = new ArrayList<>();
    private String page="", query="", error="", bone="";
    private YsmStudio.Snapshot model;
    private YsmStudio.BonePose pendingPose;
    private long pollAt, poseUntil;
    YsmStudioPane() { add(status); add(search); add(expression); add(list); }
    void open(String tab) {
        page=tab; error=""; model=null; pollAt=0; pendingPose=null; bone="";
        search.value(""); expression.value(""); list.restore(0);
        refresh(null);
    }
    private void filter(String text) {
        query=text.toLowerCase(Locale.ROOT);
        list.restore(0);
        rebuild();
    }
    private boolean matches(String value) { return query.isEmpty() || value.toLowerCase(Locale.ROOT).contains(query); }
    @Override protected void tick(long now) {
        if (now<pollAt) return;
        pollAt=now+150_000_000L;
        var next=YsmSelectorBinding.studioSnapshot();
        if (next!=model) refresh(next);
    }
    private void refresh(YsmStudio.Snapshot next) {
        var old=model;
        model=next;
        boolean different=old==null || next==null || !old.model().equals(next.model());
        if (different) {
            pendingPose=null;
            bone=next==null || next.bones().isEmpty() ? "" : next.bones().getFirst();
            list.restore(0);
        }
        if (pendingPose!=null && (System.nanoTime()>poseUntil || pendingPose.equals(model.poses().get(bone)))) pendingPose=null;
        boolean debug="Debug".equals(page);
        search.visible(next!=null); list.visible(next!=null); expression.visible(next!=null&&debug);
        if (different || structureChanged(old,next)) rebuild();
        for (int i=0;i<updates.size();i++) updates.get(i).run();
        status();
        if (different || !old.texture().equals(next.texture()) || !old.values().equals(next.values())) repaint();
    }
    private boolean structureChanged(YsmStudio.Snapshot a,YsmStudio.Snapshot b) {
        return switch(page) {
            case "Parameters" -> !a.parameters().equals(b.parameters()) || !a.textures().equals(b.textures());
            case "Actions" -> !a.animations().equals(b.animations()) || !a.animation().equals(b.animation());
            case "Pose" -> !a.bones().equals(b.bones());
            case "Debug" -> !a.controllers().keySet().equals(b.controllers().keySet()) ||
                    !a.variables().keySet().equals(b.variables().keySet()) || !a.diagnostics().equals(b.diagnostics()) ||
                    !query.isEmpty() && !a.controllers().equals(b.controllers());
            default -> false;
        };
    }
    private void status() {
        String title=model==null ? "Select a model on the Models tab." : model.model();
        String detail=!error.isEmpty()?error:model==null?"":model.result();
        status.text(title,detail);
    }
    private void act(Runnable command) {
        try { command.run(); error=""; }
        catch (RuntimeException e) { error=e.getMessage()!=null?e.getMessage():e.getClass().getSimpleName(); }
        status(); repaint();
    }
    @Override protected void layout() {
        float w=Math.max(0,width-8), top=y+2;
        status.bounds(x+4,top,w,status.preferredHeight()); top+=status.height()+2;
        if (expression.visible()) { expression.bounds(x+5,top,w-2,16); top+=19; }
        if (search.visible()) { search.bounds(x+5,top,w-2,16); top+=19; }
        list.bounds(x+1,top,width-2,Math.max(0,y+height-top-2));
    }
    private void rebuild() {
        for (Widget row:rows) list.remove(row);
        rows.clear(); updates.clear();
        if (model==null) return;
        switch(page) {
            case "Parameters" -> parameters();
            case "Actions" -> actions();
            case "Pose" -> pose();
            case "Debug" -> debug();
        }
        for (int i=0;i<updates.size();i++) updates.get(i).run();
        relayout();
    }
    private <T extends Widget> T row(T w) { rows.add(w);list.add(w);return w; }
    private YsmStudioRow line(String caption,String detail) {
        return row(new YsmStudioRow(caption,detail,Floating.LABEL,null,null,true));
    }
    private YsmStudioRow action(String title,String detail,Runnable op,BooleanSupplier chosen) {
        return row(new YsmStudioRow(title,detail,Floating.MODULE,()->act(op),chosen,false));
    }
    private void heading(String text) { row(new YsmStudioRow(text,"",Theme.TEXT,null,null,false)); }
    private void parameters() {
        action("Reset model parameters","",YsmSelectorBinding::resetParameters,null);
        var groups=new LinkedHashMap<String,List<YsmStudio.Parameter>>();
        for (var p:model.parameters()) if (matches(p.title()+p.description()+p.group()))
            groups.computeIfAbsent(p.group(),unused->new ArrayList<>()).add(p);
        for (var group:groups.entrySet()) {
            heading(group.getKey());
            for (var p:group.getValue()) parameter(p);
        }
        if (model.parameters().isEmpty()) line("No authored parameter forms.","");
        heading("Textures");
        for (String texture:model.textures())
            action(texture,"",()->YsmSelectorBinding.texture(texture),()->texture.equals(model.texture()));
    }
    private void parameter(YsmStudio.Parameter p) {
        String id=p.id();
        if ("checkbox".equals(p.kind())) {
            var check=row(new ToggleSwitch(p.title(),()->act(()->YsmSelectorBinding.parameter(id,
                    checked(id) ? 0 : 1)),()->checked(id)));
            check.tooltip(p.description());
            var hint=line("Using model default","");
            updates.add(()->hint.visible(!model.values().containsKey(id)));
        } else if ("radio".equals(p.kind()) && !p.labels().isEmpty()) {
            heading(p.title());
            for (var option:p.labels().entrySet()) {
                double n;
                try { n=Double.parseDouble(option.getKey()); } catch (NumberFormatException ignored) { continue; }
                action(option.getValue(),"",()->YsmSelectorBinding.parameter(id,n),
                        ()->parameterValue(id)==n);
            }
            var hint=line("Using model default","");
            updates.add(()->hint.visible(!model.values().containsKey(id)));
        } else slider(p.title(),p.min(),p.max(),p.step(),()->parameterValue(id),
                n->YsmSelectorBinding.parameter(id,n),false);
        if (!p.description().isBlank()) line(p.description(),"");
    }
    private double parameterValue(String id) {
        Double value=model.values().get(id);
        return value==null?Double.NaN:value;
    }
    private boolean checked(String id) {
        Double value=model.values().get(id);
        return value!=null && value!=0;
    }
    private static double decimal(double value) {
        return value==(double)(float)value ? Double.parseDouble(Float.toString((float)value)) : value;
    }
    private void slider(String label,double min,double max,double step,DoubleSupplier read,DoubleConsumer write,boolean seek) {
        if (!(max>min) || !Double.isFinite(max-min)) return;
        double n=read.getAsDouble();
        try {
            var control=new Slider(label,decimal(min),decimal(max),decimal(step),Double.isNaN(n)?0:n,max,false,
                    (v,unused)->act(()->write.accept(v)));
            if (seek) control.commitOnRelease();
            row(control);
            updates.add(new Runnable() {
                double seen=Double.POSITIVE_INFINITY;
                public void run() {
                    double value=read.getAsDouble();
                    if (Double.compare(seen,value)==0) return;
                    seen=value;
                    control.sync(Double.isNaN(value)?0:value,0);
                    control.valueHint(Double.isNaN(value)?"Model default":"");
                }
            });
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Authored continuous or high-precision ranges cannot use integral tick indexing.
            var control=row(new YsmFreeSlider(label,min,max,step,read,number->act(()->write.accept(number)),seek));
            updates.add(control::sync);
        }
    }
    private void actions() {
        var pause=action(model.paused()?"Resume":"Pause","",()->YsmSelectorBinding.playback(model.speed(),!model.paused()),null);
        updates.add(()->pause.text(model.paused()?"Resume":"Pause",""));
        action("Restart","",()->YsmSelectorBinding.play(model.animation()),null);
        action("Automatic actions","",()->YsmSelectorBinding.play(""),()->model.animation().isEmpty());
        var current=line(model.animation().isEmpty()?"Automatic":model.animation(),"");
        updates.add(()->current.text(model.animation().isEmpty()?"Automatic":model.animation(),
                com.blanoir.tempos.client.utils.text.NumberText.trimmedDecimal(model.time(),2)+" s"));
        slider("Playback speed",.05,4,.05,()->model.speed(),v->YsmSelectorBinding.playback(v,model.paused()),false);
        double duration=30;
        for (var a:model.animations()) if (a.id().equals(model.animation()) && a.duration()>0) duration=Math.min(300,a.duration());
        slider("Timeline",0,duration,.05,()->model.time(),YsmSelectorBinding::seek,true);
        for (var a:model.animations()) if (matches(a.label()+a.id()+a.group()))
            action(a.label(),a.group()+" · "+a.id(),()->YsmSelectorBinding.play(a.id()),()->a.id().equals(model.animation()));
    }
    private YsmStudio.BonePose currentPose() { return pendingPose!=null?pendingPose:model.poses().getOrDefault(bone,REST); }
    private static double axis(YsmStudio.BonePose p,int i) {
        return switch(i) { case 0->p.x();case 1->p.y();case 2->p.z();case 3->p.pitch();case 4->p.yaw();
            case 5->p.roll();case 6->p.scaleX();case 7->p.scaleY();default->p.scaleZ(); };
    }
    private void setPose(int axis,double n,boolean hidden) {
        var p=currentPose();
        var next=new YsmStudio.BonePose(axis==0?n:p.x(),axis==1?n:p.y(),axis==2?n:p.z(),
                axis==3?n:p.pitch(),axis==4?n:p.yaw(),axis==5?n:p.roll(),axis==6?n:p.scaleX(),
                axis==7?n:p.scaleY(),axis==8?n:p.scaleZ(),hidden);
        YsmSelectorBinding.pose(bone,next);
        pendingPose=next; poseUntil=System.nanoTime()+750_000_000L;
    }
    private void pose() {
        var bones=model.bones().stream().filter(this::matches).toList();
        row(new Choice("Bone",bones,()->bone,value->{bone=value;pendingPose=null;rebuild();}));
        action("Reset pose","",()->{YsmSelectorBinding.resetPose();pendingPose=null;},null);
        if (bone.isEmpty()) return;
        action("Reset bone","",()->{YsmSelectorBinding.pose(bone,null);pendingPose=null;},null);
        line("Position in model pixels · rotation in degrees","");
        row(new ToggleSwitch("Hide bone and children",()->act(()->setPose(-1,0,!currentPose().hidden())),
                ()->currentPose().hidden()));
        for (int i=0;i<AXES.length;i++) {
            int index=i;
            slider(AXES[i],i<3?-64:i<6?-180:0,i<3?64:i<6?180:4,i<3?.1:i<6?1:.01,
                    ()->axis(currentPose(),index),v->setPose(index,v,currentPose().hidden()),false);
        }
    }
    private void debug() {
        action("Evaluate","",()->YsmSelectorBinding.evaluate(expression.value()),null);
        heading("Active controllers");
        for (var entry:model.controllers().entrySet()) if (matches(entry.getKey()+entry.getValue())) {
            String id=entry.getKey();
            var row=line(id,entry.getValue());
            updates.add(()->row.text(id,model.controllers().get(id)));
        }
        heading("Model variables");
        for (var id:model.variables().keySet()) if (matches(id)) {
            var row=line("v."+id,"");
            updates.add(new Runnable() {
                double seen=Double.NaN;
                public void run() {
                    double n=model.variables().get(id);
                    if (Double.compare(seen,n)!=0) { seen=n;row.text("v."+id+" = "+n,""); }
                }
            });
        }
        if (!model.diagnostics().isEmpty()) {
            heading("Diagnostics");
            for (String d:model.diagnostics()) row(new YsmStudioRow(d,"",Theme.DANGER,null,null,true));
        }
    }
}
