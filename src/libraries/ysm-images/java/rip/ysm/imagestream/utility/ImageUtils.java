package rip.ysm.imagestream.utility;

import java.awt.image.BufferedImage;

public class ImageUtils {
   public static BufferedImage fixSubBufferedImage(BufferedImage image) {
      if (image.getRaster().getParent() != null) {
         return image.getSubimage(0, 0, image.getWidth(), image.getHeight());
      }
      return image;
   }
}
