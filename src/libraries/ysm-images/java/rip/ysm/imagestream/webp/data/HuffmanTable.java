package rip.ysm.imagestream.webp.data;

import java.util.Arrays;

final class HuffmanTable {
   private static final int[] KCODELENGTHCODEORDER = new int[]{17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

   private int singleSymbol = -1;
   private int maxLen;
   private short[] fastSymbol;
   private byte[] fastLength;

   HuffmanTable(WBit wBit, int alphabetSize) {
      boolean simpleLengthCode = wBit.readBit() == 1;
      if (simpleLengthCode) {
         int symbolNum = wBit.readBit() + 1;
         boolean first8Bits = wBit.readBit() == 1;
         int symbol1 = wBit.readBits(first8Bits ? 8 : 1);
         if (symbolNum == 2) {
            int symbol2 = wBit.readBits(8);
            short[] codeLengths = new short[Math.max(symbol1, symbol2) + 1];
            codeLengths[symbol1] = 1;
            codeLengths[symbol2] = 1;
            this.buildFromLengths(codeLengths);
         } else {
            this.singleSymbol = symbol1;
         }
      } else {
         int numLCodeLengths = wBit.readBits(4) + 4;
         short[] lCodeLengths = new short[KCODELENGTHCODEORDER.length];
         int numPosCodeLens = 0;

         for (int i = 0; i < numLCodeLengths; i++) {
            short len = (short)wBit.readBits(3);
            lCodeLengths[KCODELENGTHCODEORDER[i]] = len;
            if (len > 0) {
               numPosCodeLens++;
            }
         }

         short[] codeLengths = readCodeLengths(wBit, lCodeLengths, alphabetSize, numPosCodeLens);
         this.buildFromLengths(codeLengths);
      }
   }

   private HuffmanTable(short[] codeLengths, int numPosCodeLens) {
      this.buildFromLengths(codeLengths);
   }

   private void buildFromLengths(short[] codeLengths) {
      int count = 0;
      int max = 0;
      for (short codeLength : codeLengths) {
         if (codeLength != 0) {
            count++;
            if (codeLength > max) {
               max = codeLength;
            }
         }
      }

      if (count == 0) {
         return;
      }
      if (count == 1) {
         for (int i = 0; i < codeLengths.length; i++) {
            if (codeLengths[i] != 0) {
               this.singleSymbol = i;
               return;
            }
         }
      }

      // Symbols ordered by (length, symbol); canonical codes via the VP8L bit-reversed scheme.
      int[] lengthsAndSymbols = new int[count];
      int index = 0;
      for (int i = 0; i < codeLengths.length; i++) {
         if (codeLengths[i] != 0) {
            lengthsAndSymbols[index++] = codeLengths[i] << 16 | i;
         }
      }
      Arrays.sort(lengthsAndSymbols);

      this.maxLen = max;
      int tableSize = 1 << max;
      this.fastSymbol = new short[tableSize];
      this.fastLength = new byte[tableSize];

      int code = 0;
      for (int lengthAndSymbol : lengthsAndSymbols) {
         int length = lengthAndSymbol >>> 16;
         int symbol = lengthAndSymbol & 0xFFFF;
         for (int slot = code; slot < tableSize; slot += 1 << length) {
            this.fastSymbol[slot] = (short)symbol;
            this.fastLength[slot] = (byte)length;
         }
         code = nextCode(code, length);
      }
   }

   private static int nextCode(int code, int length) {
      int a = ~code & (1 << length) - 1;
      int step = Integer.highestOneBit(a);
      return code & step - 1 | step;
   }

   private static short[] readCodeLengths(WBit wBit, short[] aCodeLengths, int alphabetSize, int numPosCodeLens) {
      HuffmanTable huffmanTable = new HuffmanTable(aCodeLengths, numPosCodeLens);
      int codedSymbols;
      if (wBit.readBit() == 1) {
         int maxSymbolBitLength = 2 + 2 * wBit.readBits(3);
         codedSymbols = 2 + wBit.readBits(maxSymbolBitLength);
      } else {
         codedSymbols = alphabetSize;
      }

      short[] codeLengths = new short[alphabetSize];
      short prevLength = 8;

      for (int i = 0; i < alphabetSize && codedSymbols > 0; codedSymbols--) {
         short len = huffmanTable.readSymbol(wBit);
         if (len < 16) {
            codeLengths[i] = len;
            if (len != 0) {
               prevLength = len;
            }
         } else {
            short repeatSymbol = 0;
            int extraBits = 0;
            int repeatOffset = 0;
            switch (len) {
               case 16:
                  repeatSymbol = prevLength;
                  extraBits = 2;
                  repeatOffset = 3;
                  break;
               case 17:
                  extraBits = 3;
                  repeatOffset = 3;
                  break;
               case 18:
                  extraBits = 7;
                  repeatOffset = 11;
            }

            int repeatCount = wBit.readBits(extraBits) + repeatOffset;
            Arrays.fill(codeLengths, i, i + repeatCount, repeatSymbol);
            i += repeatCount - 1;
         }

         i++;
      }

      return codeLengths;
   }

   short readSymbol(WBit wBit) {
      if (this.singleSymbol >= 0) {
         return (short)this.singleSymbol;
      }

      int index = wBit.readForward(this.maxLen);
      int length = this.fastLength[index];
      wBit.readBits(length);
      return this.fastSymbol[index];
   }
}
