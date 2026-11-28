package com.blanoir.tempos.client.module.impl.render.xray;

import com.blanoir.tempos.client.render.world.BlockUnionMesh;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure grouping of the immutable scanner snapshot; no world or graphics access. */
public final class OreGroups {
    public record Group(XrayTarget target,BlockUnionMesh mesh) {}
    private static final class Bucket {
        final XrayTarget target;
        final LongOpenHashSet remaining=new LongOpenHashSet();
        Bucket(XrayTarget target){this.target=target;}
    }
    private static final int[][] NEIGHBORS={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private List<OreCache.CachedXrayBlock> source;
    private List<Group> cached=List.of();
    public OreGroups() {}
    public List<Group> get(List<OreCache.CachedXrayBlock> snapshot) {
        if(source!=snapshot){cached=build(snapshot);source=snapshot;}
        return cached;
    }
    public void clear(){source=null;cached=List.of();}
    public static List<Group> build(List<OreCache.CachedXrayBlock> snapshot) {
        var buckets=new LinkedHashMap<String,Bucket>();
        for(var block:snapshot)buckets.computeIfAbsent(block.target().commandName(),ignored->new Bucket(block.target())).remaining.add(block.pos().asLong());
        var groups=new ArrayList<Group>();
        var queue=new LongArrayList();
        for(Bucket bucket:buckets.values())for(long seed:bucket.remaining.toLongArray()) {
            if(!bucket.remaining.remove(seed))continue;
            queue.clear();queue.add(seed);
            for(int i=0;i<queue.size();i++) {
                long p=queue.getLong(i);int x=BlockPos.getX(p),y=BlockPos.getY(p),z=BlockPos.getZ(p);
                for(int[] d:NEIGHBORS) {
                    long next=BlockPos.asLong(x+d[0],y+d[1],z+d[2]);
                    if(bucket.remaining.remove(next))queue.add(next);
                }
            }
            groups.add(new Group(bucket.target,BlockUnionMesh.of(queue.toLongArray())));
        }
        return List.copyOf(groups);
    }
}
