import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Animated GIF encoder, for turning a tuner theme into something that can be
 * dropped into a README or a mod page.
 *
 * <p>Nothing here is specific to this project except the assumption in
 * {@link #buildPalette} about what the frames look like. It uses the GIF writer
 * that ships with the JDK, so the tuning tools stay dependency-free.
 *
 * <h2>Two things about GIF that shape everything else</h2>
 *
 * <p><b>Frame delay is in hundredths of a second.</b> Not milliseconds. So the
 * only frame rates that survive the file format exactly are the ones that
 * divide 100 — 10, 20, 25 and 50 fps. 30 fps is 3.33 centiseconds, gets written
 * as 3, and plays back 11% fast. That is invisible on a one-shot animation and
 * fatal on a looping one: the recording and the playback disagree about how
 * long the loop is, so the seam drifts. {@link #delayCentis} therefore takes
 * centiseconds directly rather than an fps figure, to keep the caller honest
 * about sampling at exactly the interval the file will play at.
 *
 * <p><b>256 colours, and a palette per frame if you let it.</b> Letting each
 * frame pick its own palette makes a dark board with a coloured glow on it
 * shimmer between frames, because the quantiser keeps making slightly different
 * choices about which near-blacks to keep. That reads as noise over the whole
 * image and it is far more noticeable than the banding it saves you. So the
 * palette here is built once across every frame and shared.
 */
public final class GifWriter {

    /** Infinite, in the Netscape extension's counting. */
    public static final int LOOP_FOREVER = 0;

    private GifWriter() {
    }

    /**
     * Writes an animated GIF.
     *
     * @param delayCentis per-frame delay in hundredths of a second — see the
     *                    class doc for why this is not milliseconds
     * @param loopCount   {@link #LOOP_FOREVER}, or a finite number of plays
     */
    public static void write(Path out, List<BufferedImage> frames, int delayCentis, int loopCount)
            throws IOException {
        if (frames.isEmpty()) throw new IOException("no frames to write");

        IndexColorModel palette = buildPalette(frames);
        List<BufferedImage> indexed = new ArrayList<>(frames.size());
        for (BufferedImage f : frames) indexed.add(toIndexed(f, palette));

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) throw new IOException("no GIF writer in this JVM");
        ImageWriter writer = writers.next();

        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out.toFile())) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(indexed.get(0));
            IIOMetadata meta = writer.getDefaultImageMetadata(type, params);
            configure(meta, delayCentis, loopCount);

            writer.setOutput(stream);
            // Stream metadata carrying the palette as the GIF's global colour
            // table. Passing null here instead — which is what every example of
            // this on the internet does — writes a default global table and
            // then encodes the frames against it, ignoring the palette on the
            // images entirely. The indices come through intact and land on the
            // wrong colours, so the file is valid, the right size, the right
            // length, and every colour in it is wrong.
            writer.prepareWriteSequence(streamMetadata(writer, params, palette,
                    indexed.get(0).getWidth(), indexed.get(0).getHeight()));
            // One metadata object for every frame. The delay is constant and the
            // loop extension is only read from the first frame, so there is
            // nothing per-frame to vary.
            for (BufferedImage f : indexed) {
                writer.writeToSequence(new IIOImage(f, null, meta), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Sets the frame delay and the loop flag on a frame's metadata tree.
     *
     * <p>The loop flag is the NETSCAPE2.0 application extension — a convention
     * from 1995 that every decoder implements and no version of the GIF
     * specification mentions. Without it a viewer plays the frames once and
     * stops on the last one, which for these animations looks like the effect
     * froze rather than that the file ended.
     */
    private static void configure(IIOMetadata meta, int delayCentis, int loopCount)
            throws IOException {
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        // Drop the per-frame colour table. The palette is global now, so a copy
        // on every frame is 768 bytes each of the same 256 colours, and having
        // two tables that could disagree is a state worth not having.
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equals("LocalColorTable")) {
                root.removeChild(root.item(i));
                break;
            }
        }

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        // "none" because every frame here is full-size and fully opaque, so
        // there is nothing underneath worth restoring. The alternatives exist
        // for partial frames and produce a visible flicker on full ones.
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("transparentColorIndex", "0");
        gce.setAttribute("delayTime", String.valueOf(delayCentis));

        IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
        app.setAttribute("applicationID", "NETSCAPE");
        app.setAttribute("authenticationCode", "2.0");
        // Sub-block: a 1 to say "looping follows", then the count little-endian.
        app.setUserObject(new byte[]{
                0x1, (byte) (loopCount & 0xFF), (byte) ((loopCount >> 8) & 0xFF)});
        child(root, "ApplicationExtensions").appendChild(app);

        meta.setFromTree(format, root);
    }

    /**
     * Stream metadata declaring the shared palette as the global colour table.
     *
     * <p>This is what makes one palette across every frame actually happen in
     * the file rather than only in memory: the table is written once in the
     * header, and each frame is a block of indices into it. It is also most of
     * why the files are not enormous — a local table on every frame would add
     * 768 bytes per frame, which on a six-second animation is a hundred
     * kilobytes of the same 256 colours repeated.
     */
    private static IIOMetadata streamMetadata(ImageWriter writer, ImageWriteParam params,
                                              IndexColorModel palette, int width, int height)
            throws IOException {
        IIOMetadata meta = writer.getDefaultStreamMetadata(params);
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode screen = child(root, "LogicalScreenDescriptor");
        screen.setAttribute("logicalScreenWidth", String.valueOf(width));
        screen.setAttribute("logicalScreenHeight", String.valueOf(height));
        screen.setAttribute("colorResolution", "8");
        screen.setAttribute("pixelAspectRatio", "0");

        IIOMetadataNode table = child(root, "GlobalColorTable");
        table.setAttribute("sizeOfGlobalColorTable", "256");
        table.setAttribute("backgroundColorIndex", "0");
        table.setAttribute("sortFlag", "FALSE");
        while (table.getLength() > 0) table.removeChild(table.item(0));

        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        palette.getReds(r);
        palette.getGreens(g);
        palette.getBlues(b);
        for (int i = 0; i < 256; i++) {
            IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
            entry.setAttribute("index", String.valueOf(i));
            entry.setAttribute("red", String.valueOf(r[i] & 0xFF));
            entry.setAttribute("green", String.valueOf(g[i] & 0xFF));
            entry.setAttribute("blue", String.valueOf(b[i] & 0xFF));
            table.appendChild(entry);
        }

        meta.setFromTree(format, root);
        return meta;
    }

    /** The named child of a metadata node, appended empty if it is not there yet. */
    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    // ---------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------

    /**
     * One 256-colour palette covering every frame, by median cut.
     *
     * <p>Median cut rather than a fixed colour cube because of what these images
     * are: a nearly black field with a few saturated hues glowing out of it. A
     * uniform cube spends most of its entries on colours that never appear and
     * leaves perhaps a dozen for the dark end, where almost all of the image
     * actually lives — the glow falloff around each key then collapses into four
     * or five visible steps. Median cut spends its entries where the colours
     * are, which for these frames means most of them on the dark end.
     *
     * <p>Not dithered, deliberately. Dithering trades banding for noise, and on
     * a large flat dark background that noise is both more visible than the
     * banding and much more expensive — GIF compresses runs of identical
     * pixels, and dithering is precisely the destruction of those runs.
     */
    private static IndexColorModel buildPalette(List<BufferedImage> frames) {
        // 5 bits per channel. Full 24-bit precision would make the initial
        // bucket enormous for no gain: the output is 8-bit anyway, and 32768
        // cells already separate the colours far more finely than the palette
        // can represent.
        int[] histogram = new int[1 << 15];
        // Full-precision sums alongside the bucket counts. Averaging the bucket
        // centres instead is a whole quantisation step of error on top of the
        // one the palette already costs, and it shows: the board background at
        // #0E0E10 came back as #080810, a flat shift across every dark pixel in
        // the image, which is the most visible place to be wrong.
        long[] sumR = new long[1 << 15], sumG = new long[1 << 15], sumB = new long[1 << 15];
        for (BufferedImage f : frames) {
            int[] px = f.getRGB(0, 0, f.getWidth(), f.getHeight(), null, 0, f.getWidth());
            for (int rgb : px) {
                int cell = quantise(rgb);
                histogram[cell]++;
                sumR[cell] += (rgb >> 16) & 0xFF;
                sumG[cell] += (rgb >> 8) & 0xFF;
                sumB[cell] += rgb & 0xFF;
            }
        }

        List<int[]> colours = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<Integer> cells = new ArrayList<>();
        for (int cell = 0; cell < histogram.length; cell++) {
            if (histogram[cell] == 0) continue;
            colours.add(new int[]{(cell >> 10) & 31, (cell >> 5) & 31, cell & 31});
            weights.add(histogram[cell]);
            cells.add(cell);
        }

        List<List<Integer>> boxes = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < colours.size(); i++) all.add(i);
        boxes.add(all);

        // Split the box with the widest channel spread until we have a full
        // palette or nothing left worth splitting.
        while (boxes.size() < 256) {
            int target = -1, targetChannel = 0;
            double widest = 0;
            for (int b = 0; b < boxes.size(); b++) {
                if (boxes.get(b).size() < 2) continue;
                for (int ch = 0; ch < 3; ch++) {
                    int lo = 31, hi = 0;
                    for (int i : boxes.get(b)) {
                        int v = colours.get(i)[ch];
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                    if (hi - lo > widest) {
                        widest = hi - lo;
                        target = b;
                        targetChannel = ch;
                    }
                }
            }
            if (target < 0 || widest == 0) break;

            List<Integer> box = boxes.remove(target);
            final int ch = targetChannel;
            box.sort((a, b) -> Integer.compare(colours.get(a)[ch], colours.get(b)[ch]));
            // Split at the median by population, not by index: a handful of
            // bright keys against a hundred thousand dark pixels would otherwise
            // get half the palette to themselves.
            long total = 0;
            for (int i : box) total += weights.get(i);
            long running = 0;
            int cut = 0;
            for (int i = 0; i < box.size() - 1; i++) {
                running += weights.get(box.get(i));
                if (running * 2 >= total) {
                    cut = i + 1;
                    break;
                }
            }
            if (cut == 0) cut = box.size() / 2;
            boxes.add(new ArrayList<>(box.subList(0, cut)));
            boxes.add(new ArrayList<>(box.subList(cut, box.size())));
        }

        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        for (int i = 0; i < boxes.size() && i < 256; i++) {
            long sr = 0, sg = 0, sb = 0, w = 0;
            for (int idx : boxes.get(i)) {
                int cell = cells.get(idx);
                sr += sumR[cell];
                sg += sumG[cell];
                sb += sumB[cell];
                w += histogram[cell];
            }
            if (w == 0) continue;
            r[i] = (byte) (sr / w);
            g[i] = (byte) (sg / w);
            b[i] = (byte) (sb / w);
        }
        return new IndexColorModel(8, 256, r, g, b);
    }

    /** A colour's 5-bit-per-channel histogram cell. */
    private static int quantise(int rgb) {
        return (((rgb >> 19) & 31) << 10) | (((rgb >> 11) & 31) << 5) | ((rgb >> 3) & 31);
    }

    /**
     * Maps a frame onto the shared palette, nearest colour wins.
     *
     * <p>Drawing the image into an indexed BufferedImage would be shorter and
     * would silently dither; this is the explicit version, and it memoises by
     * 15-bit cell so the inner loop is a table lookup rather than a 256-entry
     * search per pixel.
     */
    private static BufferedImage toIndexed(BufferedImage src, IndexColorModel palette) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, palette);
        int size = palette.getMapSize();
        byte[] pr = new byte[size], pg = new byte[size], pb = new byte[size];
        palette.getReds(pr);
        palette.getGreens(pg);
        palette.getBlues(pb);

        int[] memo = new int[1 << 15];
        java.util.Arrays.fill(memo, -1);
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        java.awt.image.WritableRaster raster = out.getRaster();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = px[y * w + x];
                int cell = quantise(rgb);
                int index = memo[cell];
                if (index < 0) {
                    int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
                    int best = 0, bestDist = Integer.MAX_VALUE;
                    for (int i = 0; i < size; i++) {
                        int dr = cr - (pr[i] & 0xFF);
                        int dg = cg - (pg[i] & 0xFF);
                        int db = cb - (pb[i] & 0xFF);
                        int d = dr * dr + dg * dg + db * db;
                        if (d < bestDist) {
                            bestDist = d;
                            best = i;
                        }
                    }
                    index = best;
                    memo[cell] = index;
                }
                raster.setSample(x, y, 0, index);
            }
        }
        return out;
    }
}
