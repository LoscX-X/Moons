package rip.ysm.imagestream.utility;

import java.io.IOException;

public interface DataWriter {
   void putU8(int var1) throws IOException;
   void putU16(int var1) throws IOException;
   void putU24(int var1) throws IOException;
   void putU32(int var1) throws IOException;
   void putU64(long var1) throws IOException;
   void write(byte[] var1) throws IOException;
   int getLength();
   void close() throws IOException;
}
