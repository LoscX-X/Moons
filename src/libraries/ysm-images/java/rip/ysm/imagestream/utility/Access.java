package rip.ysm.imagestream.utility;

import java.awt.image.BufferedImage;

public final class Access {
   private Access() {}

   public static PixGet getPixGet(BufferedImage image) {
      return new PixGet() {
         @Override
         public boolean hasAlpha() { return image.getColorModel().hasAlpha(); }
         @Override
         public int getRGB(int x, int y) { return image.getRGB(x, y); }
         @Override
         public int getARGB(int x, int y) { return image.getRGB(x, y); }
      };
   }
}
