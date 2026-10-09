import com.everythingrgbprofile.color.*;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.*;
import com.everythingrgbprofile.pattern.patterns.SpiralWavePattern;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Renders contact sheets — one PNG per palette, eight frames stacked
 * vertically — so a pattern can be eyeballed against the reference GIFs side
 * by side without launching Minecraft.
 *
 * <h2>⚠ THIS DOES NOT COMPILE ANY MORE</h2>
 * It imports {@code SpiralWavePattern}, which no longer exists. That class was
 * superseded by {@code CoreEmitterPattern} once the reference animations were
 * properly fitted (see CoreEmitterPattern's class doc — the short version is
 * that the pillars turned out to be a measurable Archimedean spiral rather
 * than the wave this tool was built to preview).
 *
 * <p>Kept rather than deleted because the <b>harness</b> is still exactly
 * right: build a synthetic K70 grid, render N frames, tile them into a PNG.
 * Point the two references below at {@code CoreEmitterPattern} and it works
 * again. That's a ten-minute job the day someone needs it, versus rewriting
 * this from scratch.
 *
 * <p>Style note before anyone reformats it: yes, this is written extremely
 * densely — single-letter names, no spaces around operators, loops on one
 * line. It's a throwaway visualiser that runs from a command line, not
 * shipping code, and the terseness is deliberate. It ships nothing, imports no
 * NeoForge, and exists to be modified in place and re-run.
 *
 * <pre>
 *   javac -cp build/classes/java/main -d tuning tuning/SpiralWavePreview.java
 *   java  -cp "build/classes/java/main;tuning" SpiralWavePreview
 * </pre>
 */
public class SpiralWavePreview {
    public static void main(String[] a) throws Exception {
        new File("preview3").mkdirs();

        // A synthetic K70 in real millimetres: 6 rows of 18/21/20/19/17/14
        // keys spanning 13.6-422.7mm horizontally and 11.8-133.6mm vertically.
        // Those figures are the actual physical extents of the board, which is
        // what makes the aspect ratio — and therefore the geometry — match
        // what the mod sees from real hardware.
        KeyGrid.Builder b=new KeyGrid.Builder();
        b.beginDevice("{k}","K70",KeyGrid.DeviceClass.KEYBOARD);
        int l=1;int[] rows={18,21,20,19,17,14};
        for(int r=0;r<rows.length;r++)for(int c=0;c<rows[r];c++)
            b.addLed(l++,13.6+(422.7-13.6)*c/(rows[r]-1.0),11.8+(133.6-11.8)*r/(rows.length-1.0));
        b.endDevice(); KeyGrid g=b.build();

        // {name, gradient, rotations, arms}. The gradients are the real
        // extracted palettes from the reference pillars — see ColorRamp's
        // class doc for why they need five stops instead of two.
        Object[][] cases = {
            {"solar_t2",  List.of("#3D0E00","#FD0500","#FB3F00","#FE8200","#FEA200"), 2.0, 1.0},
            {"nebula_t2", List.of("#200819","#690750","#9E3A7E","#DE71B4","#F98BCC"), 2.0, 1.0},
            {"nebula_t3_arm2", List.of("#200819","#690750","#9E3A7E","#DE71B4","#F98BCC"), 3.0, 2.0},
            {"stardust_t1_5", List.of("#1C1E3E","#3948F9","#7F88F8","#D3D7FE","#F9FAFE"), 1.5, 1.0},
        };
        for (Object[] cs : cases) {
            String name=(String)cs[0];
            @SuppressWarnings("unchecked") List<String> hex=(List<String>)cs[1];
            var p=new SpiralWavePattern(ColorRamp.fromHex(hex,null),
                    SpiralWavePattern.REFERENCE_PERIOD_MILLIS, (Double)cs[2], (Double)cs[3], 0.5, 0.5, true);
            var ctx=new PatternContext(g,null,RGBColor.WHITE,null,0,PatternParams.EMPTY);
            // 8 frames evenly spaced across ONE full period, 8px cells, 4px
            // gutter between frames. Eight is enough to read the motion in a
            // still image without the sheet becoming unreadably tall.
            int frames=8,cell=8,gap=4,cols=22,rws=6,w=cols*cell,h=rws*cell;
            BufferedImage img=new BufferedImage(w,frames*(h+gap),BufferedImage.TYPE_INT_RGB);
            for(int f=0;f<frames;f++){
                long t=(long)(SpiralWavePattern.REFERENCE_PERIOD_MILLIS*f/(double)frames);
                var fr=p.render(ctx,t); int yo=f*(h+gap);
                for(var lp:g.allKeys()){
                    var c=fr.get(lp.ref()).color();
                    // Normalised position -> pixel, then fill a (cell-1)
                    // square so there's a 1px gap between keys. min() guards
                    // are pure paranoia against a rounding overshoot at the
                    // far edge.
                    int px=(int)(lp.x()*(w-cell)),py=yo+(int)(lp.y()*(h-cell));
                    for(int dx=0;dx<cell-1;dx++)for(int dy=0;dy<cell-1;dy++)
                        img.setRGB(Math.min(w-1,px+dx),Math.min(img.getHeight()-1,py+dy),
                                   (c.r()<<16)|(c.g()<<8)|c.b());
                }
            }
            ImageIO.write(img,"png",new File("preview3/"+name+".png"));
            System.out.println("wrote "+name);
        }
    }
}
