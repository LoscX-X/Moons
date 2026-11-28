package com.blanoir.tempos.client.render.world;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4fc;
import java.util.ArrayList;

/** Cached exterior of unique, face-connected voxels. Coplanar internal edges are cancelled. */
public final class BlockUnionMesh {
    private static final int[][] DIRECTIONS = {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    private record Face(int normal, int plane, int u, int v) {}
    private record Edge(int axis, int x, int y, int z, int length) {}
    public record Colored(BlockUnionMesh mesh, float red, float green, float blue) {}
    private final int minX, minY, minZ, maxX, maxY, maxZ, blocks;
    private final Face[] faces;
    private final Edge[] edges;

    private BlockUnionMesh(long[] positions) {
        if (positions.length == 0) throw new IllegalArgumentException("Empty block group");
        int x1=Integer.MAX_VALUE,y1=x1,z1=x1,x2=Integer.MIN_VALUE,y2=x2,z2=x2;
        for(long p:positions) {
            int x=BlockPos.getX(p),y=BlockPos.getY(p),z=BlockPos.getZ(p);
            x1=Math.min(x1,x);y1=Math.min(y1,y);z1=Math.min(z1,z);
            x2=Math.max(x2,x+1);y2=Math.max(y2,y+1);z2=Math.max(z2,z+1);
        }
        minX=x1;minY=y1;minZ=z1;maxX=x2;maxY=y2;maxZ=z2;blocks=positions.length;
        if ((long)(x2-x1)*(y2-y1)*(z2-z1)==blocks) { faces=null;edges=null;return; }
        var cells=new LongOpenHashSet(positions);
        var surface=new ArrayList<Face>();
        var masks=new Long2ByteOpenHashMap[]{new Long2ByteOpenHashMap(),new Long2ByteOpenHashMap(),new Long2ByteOpenHashMap()};
        for(long p:positions) {
            int x=BlockPos.getX(p),y=BlockPos.getY(p),z=BlockPos.getZ(p);
            for(int n=0;n<6;n++) {
                int[] d=DIRECTIONS[n];
                if(cells.contains(BlockPos.asLong(x+d[0],y+d[1],z+d[2])))continue;
                int axis=n/2, plane=(axis==0?x:axis==1?y:z)+(n&1);
                int u=axis==0?y:x,v=axis==2?y:z;
                surface.add(new Face(n,plane,u,v));
                int bit=1<<n;
                if(axis==0) {
                    edge(masks[1],plane,u,v,bit);edge(masks[1],plane,u,v+1,bit);
                    edge(masks[2],plane,u,v,bit);edge(masks[2],plane,u+1,v,bit);
                } else if(axis==1) {
                    edge(masks[0],u,plane,v,bit);edge(masks[0],u,plane,v+1,bit);
                    edge(masks[2],u,plane,v,bit);edge(masks[2],u+1,plane,v,bit);
                } else {
                    edge(masks[0],u,v,plane,bit);edge(masks[0],u,v+1,plane,bit);
                    edge(masks[1],u,v,plane,bit);edge(masks[1],u+1,v,plane,bit);
                }
            }
        }
        var outlines=new ArrayList<Edge>();
        for(int axis=0;axis<3;axis++)for(var entry:masks[axis].long2ByteEntrySet()) {
            long start=entry.getLongKey();byte mask=entry.getByteValue();
            if(masks[axis].get(step(start,axis,-1))==mask)continue;
            int length=1;
            while(masks[axis].get(step(start,axis,length))==mask)length++;
            outlines.add(new Edge(axis,BlockPos.getX(start),BlockPos.getY(start),BlockPos.getZ(start),length));
        }
        faces=surface.toArray(Face[]::new);edges=outlines.toArray(Edge[]::new);
    }
    public static BlockUnionMesh of(long[] uniquePositions) { return new BlockUnionMesh(uniquePositions); }
    private static void edge(Long2ByteOpenHashMap edges,int x,int y,int z,int bit) {
        long key=BlockPos.asLong(x,y,z);byte next=(byte)(edges.get(key)^bit);
        if(next==0)edges.remove(key);else edges.put(key,next);
    }
    private static long step(long p,int axis,int n) {
        return BlockPos.asLong(BlockPos.getX(p)+(axis==0?n:0),BlockPos.getY(p)+(axis==1?n:0),BlockPos.getZ(p)+(axis==2?n:0));
    }
    public int minX(){return minX;} public int minY(){return minY;} public int minZ(){return minZ;}
    public int maxX(){return maxX;} public int maxY(){return maxY;} public int maxZ(){return maxZ;}
    public int blockCount(){return blocks;}
    public int faceCount(){return faces==null?6:faces.length;}
    public int edgeCount(){return edges==null?12:edges.length;}
    public long surfaceArea(){return faces==null?2L*((long)(maxX-minX)*(maxY-minY)+(long)(maxX-minX)*(maxZ-minZ)+(long)(maxY-minY)*(maxZ-minZ)):faces.length;}

    public void fill(Matrix4fc pose,VertexConsumer out,float r,float g,float b,float alpha) {
        if(faces!=null)for(Face f:faces)quad(pose,out,f.normal,f.plane,f.u,f.v,1,1,r,g,b,alpha);
        else {
            quad(pose,out,0,minX,minY,minZ,maxY-minY,maxZ-minZ,r,g,b,alpha);
            quad(pose,out,1,maxX,minY,minZ,maxY-minY,maxZ-minZ,r,g,b,alpha);
            quad(pose,out,2,minY,minX,minZ,maxX-minX,maxZ-minZ,r,g,b,alpha);
            quad(pose,out,3,maxY,minX,minZ,maxX-minX,maxZ-minZ,r,g,b,alpha);
            quad(pose,out,4,minZ,minX,minY,maxX-minX,maxY-minY,r,g,b,alpha);
            quad(pose,out,5,maxZ,minX,minY,maxX-minX,maxY-minY,r,g,b,alpha);
        }
    }
    private static void quad(Matrix4fc pose,VertexConsumer out,int normal,int plane,int u,int v,int du,int dv,float r,float g,float b,float a) {
        int axis=normal/2;
        boolean reverse=(normal&1)!=(axis==1?0:1);
        for(int i=0;i<4;i++) {
            int j=reverse?(4-i)%4:i;
            int p=u+(j==1||j==2?du:0),q=v+(j>=2?dv:0);
            vertex(pose,out,axis==0?plane:p,axis==0?p:axis==1?plane:q,axis==2?plane:q,r,g,b,a);
        }
    }
    public void outline(Matrix4fc pose,VertexConsumer out,float r,float g,float b) {
        if(edges!=null)for(Edge e:edges)line(pose,out,e.x,e.y,e.z,e.axis,e.length,r,g,b);
        else {
            for(int y=0;y<2;y++)for(int z=0;z<2;z++)line(pose,out,minX,y==0?minY:maxY,z==0?minZ:maxZ,0,maxX-minX,r,g,b);
            for(int x=0;x<2;x++)for(int z=0;z<2;z++)line(pose,out,x==0?minX:maxX,minY,z==0?minZ:maxZ,1,maxY-minY,r,g,b);
            for(int x=0;x<2;x++)for(int y=0;y<2;y++)line(pose,out,x==0?minX:maxX,y==0?minY:maxY,minZ,2,maxZ-minZ,r,g,b);
        }
    }
    private static void line(Matrix4fc pose,VertexConsumer out,int x,int y,int z,int axis,int length,float r,float g,float b) {
        vertex(pose,out,x,y,z,r,g,b,1);
        vertex(pose,out,x+(axis==0?length:0),y+(axis==1?length:0),z+(axis==2?length:0),r,g,b,1);
    }
    private static void vertex(Matrix4fc m,VertexConsumer out,float x,float y,float z,float r,float g,float b,float a) {
        out.addVertex(m.m00()*x+m.m10()*y+m.m20()*z+m.m30(),m.m01()*x+m.m11()*y+m.m21()*z+m.m31(),m.m02()*x+m.m12()*y+m.m22()*z+m.m32()).setColor(r,g,b,a);
    }
}
