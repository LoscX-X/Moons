package rip.ysm.imagestream.utility;

public class DataByteBig extends DataByteReader {
   public DataByteBig(byte[] data) { super(data); }

   @Override
   public int getU16() {
      return (this.data[this.p++] & 0xFF) << 8 | this.data[this.p++] & 0xFF;
   }

   @Override
   public int getU24() {
      return (this.data[this.p++] & 0xFF) << 16 | (this.data[this.p++] & 0xFF) << 8 | this.data[this.p++] & 0xFF;
   }

   @Override
   public int getU32() {
      return (this.data[this.p++] & 0xFF) << 24 | (this.data[this.p++] & 0xFF) << 16 | (this.data[this.p++] & 0xFF) << 8 | this.data[this.p++] & 0xFF;
   }

   @Override
   public void close() {}
}
