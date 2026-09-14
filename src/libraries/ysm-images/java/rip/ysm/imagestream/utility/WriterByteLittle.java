package rip.ysm.imagestream.utility;

public class WriterByteLittle implements DataWriter {
   public byte[] data;
   public int bp;
   private int bLen = 8;

   public WriterByteLittle() { this.data = new byte[this.bLen]; }

   public WriterByteLittle(byte[] data) { this.data = data; this.bLen = data.length; }

   @Override
   public void putU8(int v) { putByte((byte)v); }

   @Override
   public void putU16(int v) { write(new byte[]{(byte)(v & 0xFF), (byte)(v >> 8 & 0xFF)}); }

   @Override
   public void putU24(int v) { write(new byte[]{(byte)(v & 0xFF), (byte)(v >> 8 & 0xFF), (byte)(v >> 16 & 0xFF)}); }

   @Override
   public void putU32(int v) { write(new byte[]{(byte)(v & 0xFF), (byte)(v >> 8 & 0xFF), (byte)(v >> 16 & 0xFF), (byte)(v >> 24 & 0xFF)}); }

   @Override
   public void putU64(long v) { putU32((int)v); putU32((int)(v >> 32)); }

   private void putByte(byte b) {
      int req = this.bp + 1;
      if (req >= this.bLen) { this.bLen = req << 1; byte[] temp = new byte[this.bLen]; System.arraycopy(this.data, 0, temp, 0, this.data.length); this.data = temp; }
      this.data[this.bp++] = b;
   }

   @Override
   public void write(byte[] inp) {
      int req = this.bp + inp.length;
      if (req >= this.bLen) { this.bLen = req << 1; byte[] temp = new byte[this.bLen]; System.arraycopy(this.data, 0, temp, 0, this.data.length); this.data = temp; }
      System.arraycopy(inp, 0, this.data, this.bp, inp.length);
      this.bp += inp.length;
   }

   public byte[] toArray() { byte[] temp = new byte[this.bp]; System.arraycopy(this.data, 0, temp, 0, this.bp); return temp; }

   @Override
   public int getLength() { return this.bp; }

   @Override
   public void close() {}
}
