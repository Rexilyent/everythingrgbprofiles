import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.effects.support.PortalTransitionEffect;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.*;
import com.everythingrgbprofile.pattern.patterns.*;
import com.everythingrgbprofile.priority.Compositor;
import com.everythingrgbprofile.priority.EffectController;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.*;

/**
 * A live pattern studio: the mod's real patterns rendered onto a drawn
 * keyboard at 30Hz, with every parameter on a slider, optionally mirrored onto
 * your actual hardware at the same time.
 *
 * <p>This is the tool that made the whole CoreEmitterPattern fitting exercise
 * tractable. Tuning a 20-parameter animation by editing a config, launching
 * Minecraft, walking to a portal, and squinting is not a workflow — it's a
 * punishment. Drag a slider and watch instead.
 *
 * <h2>The one rule that makes it trustworthy</h2>
 * Everything here calls the mod's <b>actual classes</b> — {@link Compositor},
 * {@link PortalTransitionEffect}, the real pattern implementations. Nothing is
 * reimplemented for the preview.
 *
 * <p>That matters more than it sounds. A visualiser that reimplements the
 * thing it visualises is worse than no visualiser: it agrees with reality
 * right up until the moment you're relying on it, and then quietly doesn't.
 * If it looks right here, it is right in the game. The spiral sliders drive
 * {@code CoreEmitterPattern}, the same class the portal effects use.
 *
 * <h2>Running</h2>
 * <pre>
 * gradlew compileJava
 * javac -cp build\classes\java\main -d tuning tuning\PatternStudio.java
 * java  -cp "build\classes\java\main;tuning" PatternStudio
 * </pre>
 *
 * <p>For hardware output, add JNA and have the DLL beside you:
 * <pre>
 * java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" PatternStudio
 * </pre>
 * The SDK classes load lazily, so the studio runs fine without JNA present --
 * the Drive keyboard box simply reports why it can't connect.
 */
public class PatternStudio {

    // ---------------------------------------------------------------
    // Physical layout
    // ---------------------------------------------------------------
    // ANSI full-size in key units (1u = 19.05mm). This is what gets DRAWN,
    // always — see the note on `grid` below for why connecting hardware does
    // not replace it. Being representative rather than millimetre-exact is
    // fine; it's a picture of a keyboard, not a CAD model.

    record Key(String label, double x, double y, double w, double h) {}

    static final List<Key> LAYOUT = new ArrayList<>();

    /**
     * A tiny row DSL, because writing out 104 key rectangles by hand is a
     * fate nobody deserves. Each token is either {@code Label:width}, or
     * {@code @x} to jump the cursor to an absolute column — which is how the
     * gaps between the main block, the nav cluster and the numpad get
     * expressed without any per-key coordinates at all.
     */
    static void row(double y, String... tokens) {
        double x = 0;
        for (String tk : tokens) {
            if (tk.startsWith("@")) { x = Double.parseDouble(tk.substring(1)); continue; }
            int i = tk.lastIndexOf(':');
            String label = tk.substring(0, i);
            double w = Double.parseDouble(tk.substring(i + 1));
            LAYOUT.add(new Key(label, x, y, w, 1));
            x += w;
        }
    }

    static {
        row(0, "Esc:1", "@2", "F1:1","F2:1","F3:1","F4:1", "@6.5", "F5:1","F6:1","F7:1","F8:1",
               "@11", "F9:1","F10:1","F11:1","F12:1", "@15.25", "PrS:1","ScL:1","Pau:1");
        row(1.5, "`:1","1:1","2:1","3:1","4:1","5:1","6:1","7:1","8:1","9:1","0:1","-:1","=:1","Bksp:2",
                 "@15.25","Ins:1","Hom:1","PgU:1", "@18.5","Num:1","/:1","*:1","-:1");
        row(2.5, "Tab:1.5","Q:1","W:1","E:1","R:1","T:1","Y:1","U:1","I:1","O:1","P:1","[:1","]:1","\\:1.5",
                 "@15.25","Del:1","End:1","PgD:1", "@18.5","7:1","8:1","9:1","+:1");
        row(3.5, "Caps:1.75","A:1","S:1","D:1","F:1","G:1","H:1","J:1","K:1","L:1",";:1","':1","Enter:2.25",
                 "@18.5","4:1","5:1","6:1");
        row(4.5, "Shift:2.25","Z:1","X:1","C:1","V:1","B:1","N:1","M:1",",:1",".:1","/:1","Shift:2.75",
                 "@16.25","Up:1", "@18.5","1:1","2:1","3:1","Ent:1");
        row(5.5, "Ctrl:1.25","Win:1.25","Alt:1.25","Space:6.25","Alt:1.25","Fn:1.25","Menu:1.25","Ctrl:1.25",
                 "@15.25","Lft:1","Dwn:1","Rgt:1", "@18.5","0:2",".:1");
    }

    static final double U = 19.05; // mm per key unit

    static KeyGrid buildGrid() {
        KeyGrid.Builder b = new KeyGrid.Builder();
        b.beginDevice("{studio}", "Studio Keyboard", KeyGrid.DeviceClass.KEYBOARD);
        for (int i = 0; i < LAYOUT.size(); i++) {
            Key k = LAYOUT.get(i);
            b.addLed(i, (k.x() + k.w() / 2) * U, (k.y() + k.h() / 2) * U);
        }
        for (int i = 0; i < LAYOUT.size(); i++) {
            String l = LAYOUT.get(i).label();
            if (l.length() == 1) b.addNamedKey(l.charAt(0), new KeyGrid.LedRef("{studio}", i));
        }
        b.endDevice();
        return b.build();
    }

    // ---------------------------------------------------------------
    // Palettes measured from the reference pillars
    // ---------------------------------------------------------------

    static final Map<String, List<String>> RAMPS = new LinkedHashMap<>();
    static {
        RAMPS.put("Solar (Nether)",    List.of("#3D0E00","#FD0500","#FB3F00","#FE8200","#FEA200"));
        RAMPS.put("Stardust (End)",    List.of("#1C1E3E","#3948F9","#7F88F8","#D3D7FE","#F9FAFE"));
        RAMPS.put("Nebula (Twilight)", List.of("#200819","#690750","#9E3A7E","#DE71B4","#F98BCC"));
        RAMPS.put("Vortex",            List.of("#000300","#000A00","#003C00","#006A00","#007800"));
        RAMPS.put("Aether",            List.of("#0A1520","#2E6FA0","#8FD8FF","#CFEFFF","#FFFFFF"));
    }

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    /**
     * The studio always renders against its own drawn layout. Connecting
     * hardware does NOT swap this out.
     *
     * <p>An earlier version replaced this with the real hardware grid, whose
     * LedRefs carry the device UUID, while the board still looked up
     * {@code LedRef("{studio}", i)} -- so every lookup missed and the on-screen
     * keyboard went black the moment hardware connected. Mapping the hardware
     * back onto the drawn keys fixed the symptom but kept the fragility: two
     * grids, one identity space, and a translation layer between them.
     *
     * <p>Inverting it removes the failure mode. The visualiser is the source of
     * truth; {@link HardwareBridge} translates studio LEDs to real ones on its
     * own side. The screen cannot go dark because nothing about it changes.
     */
    static final KeyGrid grid = buildGrid();

    static Map<KeyGrid.LedRef, RGBColor> frame = new HashMap<>();
    static volatile boolean playing = true;
    static long clock = 0;
    // Typed as Object, not HardwareBridge, ON PURPOSE. A typed field would
    // make the JVM load HardwareBridge when PatternStudio loads, which loads
    // ICueSdk, which needs JNA on the classpath. Typing it Object defers that
    // to the moment you actually tick the box — so the studio runs perfectly
    // with no JNA jar anywhere in sight.
    static Object hardware = null;

    // tunables
    static final CoreEmitterPattern.Settings cfg = new CoreEmitterPattern.Settings();
    static double speed = 1.0;
    static String rampName = "Solar (Nether)";
    static String mode = "Core emitter";

    static Pattern pattern;
    static PortalTransitionEffect portal;

    static ColorRamp ramp() { return ColorRamp.fromHex(RAMPS.get(rampName), null); }

    /**
     * The studio's stand-in for {@code portalTransition.holdFraction}: 72% of
     * the loop holds at full, the remaining 28% is the dissolve.
     *
     * <p>Note the studio always runs the portal in <b>fixed-stopwatch</b> mode
     * ({@code waitForWorldReady = false}). It has to: the real arrival waits
     * for {@code ReceivingLevelScreen} to close, and a Swing window has no
     * such thing, so an open-ended hold would simply never fade and you would
     * be tuning a still image. Everything else about the envelope is identical
     * to the game.
     */
    static long portalHold(long durationMillis) {
        return (long) (durationMillis * 0.72);
    }

    /** Whatever the hold leaves over. Kept as a pair so the two can't drift. */
    static long portalFade(long durationMillis) {
        return Math.max(1, durationMillis - portalHold(durationMillis));
    }

    static void rebuild() {
        ColorRamp r = ramp();
        cfg.ramp = r;
        switch (mode) {
            case "Core emitter" -> pattern = new CoreEmitterPattern(cfg);
            case "Pillar field" -> pattern = new PillarFieldPattern(r, 1000.0 / cfg.periodMillis, 0.25);
            case "Shimmer" -> pattern = new ShimmerPattern();
            case "Sweep" -> pattern = new SweepPattern();
            case "Ring expand" -> pattern = new RingExpandPattern(1.0);
            case "Twinkle" -> pattern = new TwinkleParticlePattern(8, 0.5);
            case "Full portal transition" -> {
                portal = new PortalTransitionEffect(50);
                long dur = (long) (cfg.periodMillis * 2.2);
                portal.trigger(0, r, portalHold(dur), portalFade(dur), 0, dur, false, cfg, 0);
                pattern = null;
            }
            default -> pattern = new CoreEmitterPattern(cfg);
        }
        clock = 0;
        if (turnsLabel != null) {
            // Winding is set directly now rather than emerging from the
            // emitter and wave speeds, so report it at the board edge, where
            // it is actually legible, instead of as a bare ratio.
            double rEdgeKeys = grid.extentX() / Math.max(1e-6, grid.keyWidthNormalised());
            turnsLabel.setText(String.format("winding: %.2f turns to board edge (%.1f keys)",
                    cfg.turnsWithin(rEdgeKeys), rEdgeKeys));
        }
    }

    static void step(long dtMillis) {
        if (playing) clock += (long) (dtMillis * speed);
        ColorRamp r = ramp();
        if (pattern != null) {
            PatternContext ctx = new PatternContext(grid, null, r.sample(0.85), r.sample(1.0), 1000, PatternParams.EMPTY);
            Map<KeyGrid.LedRef, LayerPixel> px = pattern.render(ctx, clock);
            Map<KeyGrid.LedRef, RGBColor> f = new HashMap<>();
            for (KeyGrid.LedPosition p : grid.allKeys()) {
                LayerPixel lp = px.get(p.ref());
                f.put(p.ref(), lp == null ? RGBColor.BLACK : lp.over(RGBColor.BLACK));
            }
            frame = f;
        } else if (portal != null) {
            long dur = (long) (cfg.periodMillis * 2.2);
            long t = clock % dur;
            // Restart the one-shot as it loops, so the transition plays on repeat.
            if (t < 40) portal.trigger(0, r, portalHold(dur), portalFade(dur), 0, dur, false, cfg, 0);
            // TIER 2, matching how EffectRegistry actually registers it. Passed
            // as tier 3 this would blank the board first, which for a preview
            // with nothing underneath looks almost identical — right up until
            // you are trying to judge the fade-out, which is the entire point
            // of previewing this effect.
            frame = Compositor.composite(List.of(), List.<EffectController>of(portal),
                    List.of(), grid, t);
        }
        pushHardware();
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    static class Board extends JPanel {
        Board() { setBackground(new Color(16, 16, 18)); setPreferredSize(new Dimension(1080, 330)); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double maxX = 0, maxY = 0;
            for (Key k : LAYOUT) { maxX = Math.max(maxX, k.x() + k.w()); maxY = Math.max(maxY, k.y() + k.h()); }
            double s = Math.min((getWidth() - 40) / maxX, (getHeight() - 40) / maxY);
            double ox = (getWidth() - maxX * s) / 2, oy = (getHeight() - maxY * s) / 2;

            for (int i = 0; i < LAYOUT.size(); i++) {
                Key k = LAYOUT.get(i);
                RGBColor c = frame.getOrDefault(new KeyGrid.LedRef("{studio}", i), RGBColor.BLACK);
                Color col = new Color(c.r(), c.g(), c.b());
                double x = ox + k.x() * s, y = oy + k.y() * s;
                double w = k.w() * s - 3, h = k.h() * s - 3;

                // A translucent glow painted UNDER the keycap so bright keys bleed
            // into their neighbours, the way real backlighting does through
            // plastic. Without it the preview reads as flat coloured squares
            // and consistently makes patterns look worse than they are on
            // hardware — which leads to tuning them wrong.
            // Alpha capped at 90 so a white key doesn't wash out the board.
                int lum = (c.r() + c.g() + c.b()) / 3;
                if (lum > 30) {
                    g.setColor(new Color(c.r(), c.g(), c.b(), Math.min(90, lum / 2)));
                    g.fill(new RoundRectangle2D.Double(x - 4, y - 4, w + 8, h + 8, 12, 12));
                }
                g.setColor(col);
                g.fill(new RoundRectangle2D.Double(x, y, w, h, 7, 7));
                g.setColor(new Color(255, 255, 255, 26));
                g.draw(new RoundRectangle2D.Double(x, y, w, h, 7, 7));

                // Legend flips black-on-light / white-on-dark at mid
                // luminance, so key labels stay readable at every brightness.
                g.setColor(lum > 128 ? new Color(0, 0, 0, 150) : new Color(255, 255, 255, 110));
                g.setFont(new Font("SansSerif", Font.PLAIN, (int) Math.max(8, s * 0.26)));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(k.label(), (int) (x + (w - fm.stringWidth(k.label())) / 2),
                        (int) (y + h / 2 + fm.getAscent() / 2.4));
            }
            // The crosshair. Small, and the most useful thing on screen when
            // tuning the emitter — everything in CoreEmitterPattern is
            // measured in radius and angle from this point, so being able to
            // see where it actually landed turns "why is it lopsided" from a
            // mystery into an observation.
            g.setColor(new Color(255, 255, 255, 120));
            int mx = (int) (ox + cfg.centerX * maxX * s), my = (int) (oy + cfg.centerY * maxY * s);
            g.drawLine(mx - 7, my, mx + 7, my);
            g.drawLine(mx, my - 7, mx, my + 7);
        }
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    static JLabel status;
    static JLabel turnsLabel;
    static JComboBox<CoreEmitterPattern.Curve> curveBox;

    static JPanel slider(String name, double min, double max, double val, java.util.function.DoubleConsumer set) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(400, 26));
        JLabel l = new JLabel(String.format("%-11s %.2f", name, val));
        l.setPreferredSize(new Dimension(120, 20));
        l.setForeground(new Color(210, 210, 215));
        l.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JSlider s = new JSlider(0, 1000, (int) ((val - min) / (max - min) * 1000));
        s.setOpaque(false);
        s.addChangeListener(e -> {
            double v = min + (max - min) * s.getValue() / 1000.0;
            l.setText(String.format("%-11s %.2f", name, v));
            set.accept(v);
            rebuild();
        });
        p.add(l, BorderLayout.WEST);
        p.add(s, BorderLayout.CENTER);
        return p;
    }

    public static void main(String[] args) {
        rebuild();
        JFrame f = new JFrame("RGB Profiles — Pattern Studio");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Board board = new Board();

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(12, 12, 12, 12));
        side.setBackground(new Color(28, 28, 32));

        JComboBox<String> modeBox = new JComboBox<>(new String[]{
                "Core emitter", "Full portal transition", "Pillar field",
                "Shimmer", "Sweep", "Ring expand", "Twinkle"});
        modeBox.addActionListener(e -> { mode = (String) modeBox.getSelectedItem(); rebuild(); });

        JComboBox<String> rampBox = new JComboBox<>(RAMPS.keySet().toArray(new String[0]));
        rampBox.addActionListener(e -> { rampName = (String) rampBox.getSelectedItem(); rebuild(); });

        JComboBox<CoreEmitterPattern.Emission> emitBox =
                new JComboBox<>(CoreEmitterPattern.Emission.values());
        emitBox.addActionListener(e -> {
            cfg.emission = (CoreEmitterPattern.Emission) emitBox.getSelectedItem();
            rebuild();
        });

        turnsLabel = new JLabel();
        turnsLabel.setForeground(new Color(150, 200, 150));
        turnsLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));

        status = new JLabel("Ready");
        status.setForeground(new Color(150, 150, 160));
        status.setFont(new Font("Monospaced", Font.PLAIN, 11));

        JButton pause = new JButton("Pause");
        pause.addActionListener(e -> { playing = !playing; pause.setText(playing ? "Pause" : "Play"); });
        JButton shot = new JButton("PNG");
        shot.addActionListener(e -> savePng(board));
        JButton json = new JButton("Copy JSON");
        json.addActionListener(e -> copyJson());

        JButton golden = new JButton("\u03c6");
        golden.setToolTipText("Solve wave velocity for a true golden spiral at the current core size");
        golden.addActionListener(e -> {
            // Winding is a direct parameter under the fitted model, so this
            // assigns the golden rate rather than solving for a velocity.
            cfg.makeGolden();
            curveBox.setSelectedItem(cfg.curve);
            rebuild();
            double rEdgeKeys = grid.extentX() / Math.max(1e-6, grid.keyWidthNormalised());
            status.setText(String.format("golden: %.3f turns/e-fold, %.2f turns to edge",
                    cfg.logTurnsPerEfold, cfg.goldenTurns(rEdgeKeys)));
        });

        JCheckBox hw = new JCheckBox("Drive real keyboard", false);
        hw.setForeground(new Color(140, 220, 140));
        hw.setOpaque(false);
        hw.addActionListener(e -> toggleHardware(hw.isSelected(), hw));

        side.add(labelled("Pattern", modeBox));
        side.add(labelled("Palette", rampBox));
        side.add(labelled("Emission", emitBox));

        curveBox = new JComboBox<>(CoreEmitterPattern.Curve.values());
        curveBox.addActionListener(e -> {
            cfg.curve = (CoreEmitterPattern.Curve) curveBox.getSelectedItem();
            rebuild();
        });
        side.add(labelled("Curve", curveBox));

        side.add(header("EMITTER"));
        side.add(check("Emitter on", cfg.emitterEnabled, v -> cfg.emitterEnabled = v));
        side.add(check("Emitter clockwise", cfg.emitterClockwise, v -> cfg.emitterClockwise = v));
        side.add(slider("glow", 0, 1, cfg.emitterGlow, v -> cfg.emitterGlow = v));
        side.add(slider("orbit speed", 0, 4, cfg.emitterSpeed, v -> cfg.emitterSpeed = v));

        side.add(header("WAVE"));
        side.add(check("Travel outward", cfg.waveOutward, v -> cfg.waveOutward = v));
        side.add(slider("velocity", 0.1, 4, cfg.waveSpeed, v -> cfg.waveSpeed = v));
        side.add(slider("arms", 1, 6, cfg.arms, v -> cfg.arms = Math.round(v)));
        side.add(slider("sharpness", 1, 10, cfg.armSharpness, v -> cfg.armSharpness = v));
        side.add(slider("falloff", 0, 1, cfg.waveFalloff, v -> cfg.waveFalloff = v));
        side.add(slider("pulse duty", 0.05, 1, cfg.pulseDuty, v -> cfg.pulseDuty = v));
        side.add(turnsLabel);

        side.add(header("CORE"));
        side.add(check("Core clockwise", cfg.coreClockwise, v -> cfg.coreClockwise = v));
        side.add(slider("size", 0, 0.6, cfg.coreRadius, v -> cfg.coreRadius = v));
        side.add(slider("orbit", 0, 1.5, cfg.coreOrbit, v -> cfg.coreOrbit = v));
        side.add(slider("orbit speed", 0, 4, cfg.coreSpeed, v -> cfg.coreSpeed = v));
        side.add(slider("wobble", 0, 1, cfg.coreWobble, v -> cfg.coreWobble = v));
        side.add(slider("centre X", 0, 1, cfg.centerX, v -> cfg.centerX = v));
        side.add(slider("centre Y", 0, 1, cfg.centerY, v -> cfg.centerY = v));

        side.add(header("TIMING"));
        side.add(slider("period ms", 200, 4000, cfg.periodMillis, v -> cfg.periodMillis = v));
        side.add(slider("playback", 0.1, 3, speed, v -> speed = v));

        side.add(Box.createVerticalStrut(6));
        side.add(hw);
        side.add(row(pause, shot, json, golden));
        side.add(status);

        JScrollPane scroll = new JScrollPane(side,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(430, 700));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);

        f.setLayout(new BorderLayout());
        f.add(board, BorderLayout.CENTER);
        f.add(scroll, BorderLayout.EAST);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        rebuild();

        new javax.swing.Timer(33, e -> { step(33); board.repaint(); }).start();
    }

    static JComponent header(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(120, 170, 230));
        l.setFont(new Font("Monospaced", Font.BOLD, 11));
        l.setBorder(new EmptyBorder(10, 0, 2, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    static JComponent check(String name, boolean initial, java.util.function.Consumer<Boolean> set) {
        JCheckBox c = new JCheckBox(name, initial);
        c.setForeground(new Color(210, 210, 215));
        c.setOpaque(false);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.addActionListener(e -> { set.accept(c.isSelected()); rebuild(); });
        return c;
    }

    static JPanel labelled(String name, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(name);
        l.setPreferredSize(new Dimension(150, 22));
        l.setForeground(new Color(210, 210, 215));
        l.setFont(new Font("Monospaced", Font.PLAIN, 12));
        p.add(l, BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        return p;
    }

    static void savePng(Board b) {
        try {
            BufferedImage img = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
            b.paint(img.getGraphics());
            File out = new File("preview3/studio_" + System.currentTimeMillis() + ".png");
            out.getParentFile().mkdirs();
            ImageIO.write(img, "png", out);
            status.setText("saved " + out.getName());
        } catch (Exception ex) { status.setText("save failed: " + ex.getMessage()); }
    }

    /** Emits the current settings in dimension_profiles.json form. */
    /** Emits the current settings as a dimension_profiles.json block. */
    static void copyJson() {
        StringBuilder sb = new StringBuilder("{\n  \"gradient\": [");
        List<String> hex = RAMPS.get(rampName);
        for (int i = 0; i < hex.size(); i++) sb.append(i > 0 ? ", " : "").append('"').append(hex.get(i)).append('"');
        sb.append("],\n");
        sb.append(String.format("  \"emissionMode\": \"%s\",%n", cfg.emission.name().toLowerCase()));
        sb.append(String.format("  \"spiralCurve\": \"%s\",%n", cfg.curve.name().toLowerCase()));
        sb.append(String.format("  \"spiralArmCount\": %d,%n", (int) cfg.arms));
        sb.append(String.format("  \"waveSpeed\": %.2f,%n", cfg.waveSpeed));
        sb.append(String.format("  \"spiralArmSharpness\": %.2f,%n", cfg.armSharpness));
        sb.append(String.format("  \"spiralWaveFalloff\": %.2f,%n", cfg.waveFalloff));
        sb.append(String.format("  \"emitterEnabled\": %s,%n", cfg.emitterEnabled));
        sb.append(String.format("  \"spiralEmitterGlow\": %.2f,%n", cfg.emitterGlow));
        sb.append(String.format("  \"emitterSpeed\": %.2f,%n", cfg.emitterSpeed));
        sb.append(String.format("  \"emitterClockwise\": %s,%n", cfg.emitterClockwise));
        sb.append(String.format("  \"spiralCoreRadius\": %.2f,%n", cfg.coreRadius));
        sb.append(String.format("  \"spiralCoreOrbit\": %.2f,%n", cfg.coreOrbit));
        sb.append(String.format("  \"coreSpeed\": %.2f,%n", cfg.coreSpeed));
        sb.append(String.format("  \"coreClockwise\": %s,%n", cfg.coreClockwise));
        sb.append(String.format("  \"coreWobble\": %.2f,%n", cfg.coreWobble));
        sb.append(String.format("  \"spiralCenterX\": %.2f,%n", cfg.centerX));
        sb.append(String.format("  \"spiralCenterY\": %.2f,%n", cfg.centerY));
        sb.append(String.format("  \"pillarPeriodMillis\": %.0f%n}", cfg.periodMillis));
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(sb.toString()), null);
        status.setText("profile JSON copied");
    }

    // ---------------------------------------------------------------
    // Optional hardware output
    // ---------------------------------------------------------------
    // HardwareBridge is a separate class so the JVM does not load it -- and
    // therefore does not need JNA on the classpath -- unless the box is ticked.

    static void toggleHardware(boolean on, JCheckBox box) {
        if (!on) {
            if (hardware != null) { ((HardwareBridge) hardware).close(); hardware = null; }
            status.setText("hardware released");
            return;
        }
        try {
            HardwareBridge b = new HardwareBridge();
            String msg = b.connect();
            if (b.ok()) {
                hardware = b;
                // The bridge maps our drawn LEDs onto its real ones. Nothing
                // on this side changes, so the visualiser keeps rendering.
                msg = msg + " | " + b.prepare(grid);
                status.setText(msg);
            } else {
                box.setSelected(false);
                status.setText(msg);
            }
        } catch (NoClassDefFoundError e) {
            box.setSelected(false);
            // Name the missing class -- "NoClassDefFoundError" alone does not
            // tell you that the fix is one jar on the classpath.
            String missing = String.valueOf(e.getMessage()).replace('/', '.');
            status.setText(missing.startsWith("com.sun.jna")
                    ? "add jna-5.14.0.jar to -cp (missing " + missing + ")"
                    : "missing class: " + missing);
        } catch (Throwable t) {
            box.setSelected(false);
            status.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    static void pushHardware() {
        if (hardware != null) ((HardwareBridge) hardware).push(frame);
    }
}
