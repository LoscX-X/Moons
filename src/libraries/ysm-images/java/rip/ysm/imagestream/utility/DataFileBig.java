package rip.ysm.imagestream.utility;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class DataFileBig extends DataFileReader {
   public DataFileBig(File f) throws IOException { super(f); }
   public DataFileBig(RandomAccessFile raf) throws IOException { super(raf); }

   @Override
   public int getU16() throws IOException { return this.getU8() << 8 | this.getU8(); }

   @Override
   public int getU24() throws IOException { return this.getU8() << 16 | this.getU8() << 8 | this.getU8(); }

   @Override
   public int getU32() throws IOException { return this.getU8() << 24 | this.getU8() << 16 | this.getU8() << 8 | this.getU8(); }
}
