package com.blanoir.tempos.client.render.world;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33C.*;

/** Opt-in GL pixels from production voxel geometry and Minecraft's actual outline shaders.
 * This small hidden context is not a Minecraft instance or an end-to-end loader test. */
public final class XrayContourGpuVerification {
    private static final int SIZE = 192;
    private static final String GEOMETRY_VERTEX = "#version 330\nlayout(location=0) in vec3 Position; uniform mat4 Projection; void main(){gl_Position=Projection*vec4(Position,1.0);}";
    private static final String GEOMETRY_FRAGMENT = "#version 330\nout vec4 color; void main(){color=vec4(0.2,0.8,1.0,1.0);}";
    private record Surface(int framebuffer, int texture) implements AutoCloseable {
        @Override public void close() { glDeleteFramebuffers(framebuffer); glDeleteTextures(texture); }
    }

    public static void main(String[] args) throws Exception {
        boolean baseline = args.length > 0 && args[0].equals("--baseline");
        Path output = Path.of(System.getProperty("tempos.contour.output", "xray-contour-gpu"));
        Files.createDirectories(output);
        require(GLFW.glfwInit(), "Unable to initialize the hidden GL fixture");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Tempos contour verification", 0, 0);
        require(window != 0, "Unable to create the hidden GL fixture");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        try {
            int geometry = program(GEOMETRY_VERTEX, GEOMETRY_FRAGMENT);
            var cube = BlockUnionMesh.of(new long[]{BlockPos.asLong(0,0,0)});
            var camera = new Matrix4f().perspective((float)Math.toRadians(40),1,.1f,100)
                    .lookAt(4,3,5,.5f,.5f,.5f,0,1,0);
            check("cube", List.of(cube), camera, geometry, baseline, output);
            check("overlap", List.of(cube,BlockUnionMesh.of(new long[]{BlockPos.asLong(-1,0,-1)})),camera,geometry,baseline,output);
            check("elbow", List.of(BlockUnionMesh.of(new long[]{BlockPos.asLong(0,0,0),BlockPos.asLong(1,0,0),BlockPos.asLong(0,0,1)})),camera,geometry,baseline,output);
            var ring = new long[8];int at=0;
            for(int x=0;x<3;x++)for(int z=0;z<3;z++)if(x!=1||z!=1)ring[at++]=BlockPos.asLong(x,0,z);
            var top = new Matrix4f().ortho(-2,2,-2,2,.1f,100).lookAt(1.5f,8,1.5f,1.5f,0,1.5f,0,0,-1);
            check("hole",List.of(BlockUnionMesh.of(ring)),top,geometry,baseline,output);
            glDeleteProgram(geometry);
            require(glGetError()==GL_NO_ERROR,"OpenGL verification reported an error");
            System.out.println("TEMPOS_XRAY_CONTOUR_GPU_VERIFIED production-geometry actual-minecraft-post-chain cube overlap elbow hole no-interior-lines hidden-context-not-Minecraft");
        } finally {
            glDeleteVertexArrays(vao);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void check(String name,List<BlockUnionMesh> meshes,Matrix4f projection,int geometry,
            boolean baseline,Path output) throws Exception {
        try(var mask=surface();var swap=surface();var lines=surface()) {
            drawGeometry(mask,meshes,projection,geometry,false);
            byte[] body=pixels(mask);
            drawGeometry(lines,meshes,projection,geometry,true);
            byte[] before=pixels(lines);
            applyMinecraftOutline(mask,swap);
            byte[] after=pixels(mask);
            save(output.resolve(name+"-comparison.png"),before,after);
            byte[] result=baseline?before:after;
            int crossing=0,edges=0,covered=0,missing=0;
            for(int y=6;y<SIZE-6;y++)for(int x=6;x<SIZE-6;x++) {
                boolean interior=true;
                for(int dy=-6;dy<=6 && interior;dy++)for(int dx=-6;dx<=6;dx++)
                    if(alpha(body,x+dx,y+dy)==0){interior=false;break;}
                if(interior && alpha(result,x,y)>0)crossing++;
                if(alpha(result,x,y)>0)edges++;
                if(alpha(body,x,y)>0 && (alpha(body,x-1,y)==0 || alpha(body,x+1,y)==0 || alpha(body,x,y-1)==0 || alpha(body,x,y+1)==0)) {
                    covered++;
                    if(alpha(result,x,y)==0)missing++;
                }
            }
            require(crossing==0,name+": hidden edges or internal creases remain inside silhouette: pixels="+crossing);
            require(covered>0 && edges>0,name+": no exterior pixels were emitted");
            require(missing==0,name+": exterior boundary pixels disappeared: "+missing);
            if(name.equals("hole")) {
                require(alpha(body,SIZE/2,SIZE/2)==0,"Ring mask filled its hole");
                require(alpha(after,SIZE/2,SIZE/2)==0,"Outline filled the center of the ring hole");
            }
            System.out.println("TEMPOS_CONTOUR_CASE "+name+" exterior_pixels="+edges+" deep_interior_pixels="+crossing);
        }
    }

    private static void drawGeometry(Surface target,List<BlockUnionMesh> meshes,Matrix4f projection,int shader,boolean wire) {
        var points=new ArrayList<Float>();
        VertexConsumer sink=(VertexConsumer)Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),new Class<?>[]{VertexConsumer.class},(proxy,method,args)->{
            if(method.getName().equals("addVertex") && args.length==3)for(Object value:args)points.add(((Number)value).floatValue());
            return method.getReturnType()==VertexConsumer.class?proxy:null;
        });
        for(var mesh:meshes)if(wire)mesh.outline(new Matrix4f(),sink,1,1,1);else mesh.fill(new Matrix4f(),sink,1,1,1,1);
        float[] positions=new float[points.size()];for(int i=0;i<positions.length;i++)positions[i]=points.get(i);
        bind(target);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_DEPTH_TEST);
        glUseProgram(shader);
        float[] matrix=new float[16];projection.get(matrix);
        glUniformMatrix4fv(glGetUniformLocation(shader,"Projection"),false,matrix);
        int vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,positions,GL_STREAM_DRAW);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0);
        if(wire)glDrawArrays(GL_LINES,0,positions.length/3);
        else {
            int[] indices=new int[positions.length/12*6];
            for(int i=0;i<indices.length/6;i++) { int v=i*4,o=i*6;indices[o]=v;indices[o+1]=v+1;indices[o+2]=v+2;indices[o+3]=v;indices[o+4]=v+2;indices[o+5]=v+3; }
            int ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STREAM_DRAW);
            glDrawElements(GL_TRIANGLES,indices.length,GL_UNSIGNED_INT,0);glDeleteBuffers(ebo);
        }
        glDisableVertexAttribArray(0);glDeleteBuffers(vbo);
    }

    private static void applyMinecraftOutline(Surface mask,Surface swap) throws Exception {
        var chain=JsonParser.parseString(asset("assets/minecraft/post_effect/entity_outline.json")).getAsJsonObject();
        for(var entry:chain.getAsJsonArray("passes")) {
            var pass=entry.getAsJsonObject();
            String output=pass.get("output").getAsString();
            Surface destination=output.equals("swap")?swap:mask;
            Surface source=destination==mask?swap:mask;
            String vertex=pass.get("vertex_shader").getAsString().replace(':','/');
            String fragment=pass.get("fragment_shader").getAsString().replace(':','/');
            int shader=program(asset(shaderPath(vertex,"vsh")),asset(shaderPath(fragment,"fsh")));
            bind(destination);glUseProgram(shader);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source.texture);
            boolean linear=pass.getAsJsonArray("inputs").get(0).getAsJsonObject().has("bilinear");
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,linear?GL_LINEAR:GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,linear?GL_LINEAR:GL_NEAREST);
            glUniform1i(glGetUniformLocation(shader,"InSampler"),0);
            var buffers=new ArrayList<Integer>();
            uniform(shader,"SamplerInfo",0,new float[]{SIZE,SIZE,SIZE,SIZE},buffers);
            if(pass.has("uniforms")) {
                int binding=1;
                for(var block:pass.getAsJsonObject("uniforms").entrySet()) {
                    var values=new ArrayList<Float>();
                    for(var field:block.getValue().getAsJsonArray()) {
                        var value=field.getAsJsonObject().get("value");
                        if(value.isJsonArray())for(var element:value.getAsJsonArray())values.add(element.getAsFloat());
                        else values.add(value.getAsFloat());
                    }
                    float[] data=new float[Math.max(4,(values.size()+3)/4*4)];for(int i=0;i<values.size();i++)data[i]=values.get(i);
                    uniform(shader,block.getKey(),binding++,data,buffers);
                }
            }
            glDrawArrays(GL_TRIANGLES,0,3);
            buffers.forEach(id->glDeleteBuffers(id));glDeleteProgram(shader);
        }
    }

    private static void uniform(int shader,String name,int binding,float[] data,List<Integer> buffers) {
        int block=glGetUniformBlockIndex(shader,name);if(block==GL_INVALID_INDEX)return;
        int buffer=glGenBuffers();buffers.add(buffer);glBindBuffer(GL_UNIFORM_BUFFER,buffer);
        glBufferData(GL_UNIFORM_BUFFER,data,GL_STREAM_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,binding,buffer);
        glUniformBlockBinding(shader,block,binding);
    }
    private static String shaderPath(String id,String suffix) { int slash=id.indexOf('/');return "assets/"+id.substring(0,slash)+"/shaders/"+id.substring(slash+1)+"."+suffix; }
    private static String asset(String name) throws Exception {
        String jar=System.getProperty("tempos.minecraft.client.jar");
        if(jar!=null)try(var zip=new ZipFile(jar)){return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        try(InputStream input=XrayContourGpuVerification.class.getClassLoader().getResourceAsStream(name)) {
            require(input!=null,"Missing actual Minecraft shader asset: "+name);
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static int program(String vertex,String fragment) {
        int program=glCreateProgram();
        for(var part:new Object[][]{{GL_VERTEX_SHADER,vertex},{GL_FRAGMENT_SHADER,fragment}}) {
            int shader=glCreateShader((int)part[0]);
            glShaderSource(shader,((String)part[1]).replace("gl_VertexIndex","gl_VertexID"));glCompileShader(shader);
            require(glGetShaderi(shader,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(shader));
            glAttachShader(program,shader);glDeleteShader(shader);
        }
        glLinkProgram(program);require(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));return program;
    }
    private static Surface surface() {
        int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,SIZE,SIZE,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        int framebuffer=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"Incomplete test framebuffer");return new Surface(framebuffer,texture);
    }
    private static void bind(Surface surface) { glBindFramebuffer(GL_FRAMEBUFFER,surface.framebuffer);glViewport(0,0,SIZE,SIZE); }
    private static byte[] pixels(Surface surface) { bind(surface);var bytes=ByteBuffer.allocateDirect(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,bytes);byte[] result=new byte[bytes.capacity()];bytes.get(result);return result; }
    private static int alpha(byte[] data,int x,int y) { return data[(y*SIZE+x)*4+3]&255; }
    private static void save(Path path,byte[] before,byte[] after) throws Exception {
        var image=new BufferedImage(SIZE*2,SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int side=0;side<2;side++)for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
            byte[] data=side==0?before:after;int at=(y*SIZE+x)*4,a=data[at+3]&255;
            int r=20+((data[at]&255)-20)*a/255,g=23+((data[at+1]&255)-23)*a/255,b=28+((data[at+2]&255)-28)*a/255;
            image.setRGB(side*SIZE+x,SIZE-1-y,0xff000000|r<<16|g<<8|b);
        }
        ImageIO.write(image,"PNG",path.toFile());
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
