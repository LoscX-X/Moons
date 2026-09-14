package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.DataByteLittle;

class WBit {
   private final DataByteLittle dataReader;
   private int bitOffset = 64;
   private int wPos = -1;
   private long buffer;

   WBit(DataByteLittle is) {
      this.dataReader = is;
   }

   int readBits(int bits) {
      return this.readBits(bits, false);
   }

   int readForward(int bits) {
      return this.readBits(bits, true);
   }

   private int readBits(int bits, boolean isForward) {
      long v;
      if (bits <= 56) {
         if (this.wPos != this.dataReader.getPosition()) {
            this.reset();
         }

         v = this.buffer >>> this.bitOffset & (1L << bits) - 1L;
         if (!isForward) {
            this.bitOffset += bits;
            if (this.bitOffset >= 8) {
               this.refill();
            }
         }
      } else {
         long lower = this.readBits(56);
         v = (long)this.readBits(bits - 56) << 56 | lower;
      }

      return (int)v;
   }

   private void refill() {
      this.getU64();

      for (; this.bitOffset >= 8; this.bitOffset -= 8) {
         long b = this.getU8Safe();
         this.buffer = b << 56 | this.buffer >>> 8;
         this.wPos++;
      }

      this.dataReader.moveTo(this.wPos);
   }

   private void reset() {
      int inputStreamPosition = this.dataReader.getPosition();
      this.buffer = this.getU64();
      this.bitOffset = 0;
      this.wPos = inputStreamPosition;
      this.dataReader.moveTo(inputStreamPosition);
   }

   int readBit() {
      return this.readBits(1);
   }

   private int getU8Safe() {
      if (this.dataReader.getPosition() < this.dataReader.getLength()) {
         return this.dataReader.getU8() & 0xFF;
      }
      this.dataReader.skip(1);
      return 0;
   }

   private long getU64() {
      long v = 0L;
      for (int i = 0; i < 8; i++) {
         v |= (long)this.getU8Safe() << (i * 8);
      }
      return v;
   }
}
