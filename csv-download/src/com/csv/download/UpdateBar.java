package com.csv.download;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Path2D;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;

/**
 * Esquina superior derecha del dialogo: version instalada, comprobacion de
 * la ultima version publicada (icono de descarga) y enlace al repositorio
 * de GitHub. El resultado de la consulta se reutiliza durante
 * {@link #CACHE_MILLIS} para no consultar GitHub cada vez que se abre el
 * dialogo (la API anonima admite 60 consultas por hora).
 */
public final class UpdateBar extends JPanel {

    private final JButton label = textButton(
            "v" + UpdateChecker.CURRENT_VERSION + " ");
    private final JButton download = iconButton(new DownloadIcon());
    private volatile UpdateChecker.Release latest;

    private static final long CACHE_MILLIS = 30L * 60L * 1000L;
    private static final Object CACHE_LOCK = new Object();
    private static UpdateChecker.Release cachedRelease;
    private static long cachedAt;

    public UpdateBar() {
        super(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        setOpaque(false);
        JButton github = iconButton(new GithubIcon());
        github.setToolTipText("Abrir el repositorio en GitHub");
        github.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                open(UpdateChecker.REPO_URL);
            }
        });
        download.setToolTipText("Comprobar la ultima version disponible");
        download.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onDownload();
            }
        });
        label.setToolTipText("Acerca de la extension");
        label.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showAbout();
            }
        });
        add(label);
        add(download);
        add(github);
        check(false);
    }

    private void onDownload() {
        UpdateChecker.Release r = latest;
        if (r == null || UpdateChecker.compare(r.version,
                UpdateChecker.CURRENT_VERSION) <= 0) {
            check(true);
        } else {
            String url = r.jarUrl != null ? r.jarUrl : r.pageUrl;
            String msg = "Hay una version nueva: v" + r.version
                    + " (instalada: v" + UpdateChecker.CURRENT_VERSION + ")\n\n"
                    + "Para actualizar:\n"
                    + "1. Descarga " + UpdateChecker.JAR_NAME
                    + " (boton \"Descargar\").\n"
                    + "2. Cierra SQL Developer.\n"
                    + "3. Copia el JAR a <carpeta de SQL Developer>\\dropins\\\n"
                    + "    reemplazando el anterior (solo debe quedar uno).\n"
                    + "4. Abre SQL Developer de nuevo.";
            Object[] opts = {"Descargar", "Ver notas de la version", "Cerrar"};
            int c = JOptionPane.showOptionDialog(this, msg, "Actualizar",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                    null, opts, opts[0]);
            if (c == 0) {
                open(url);
            } else if (c == 1) {
                open(r.pageUrl);
            }
        }
    }

    /** Consulta GitHub en un hilo aparte; interactive muestra avisos. */
    private void check(final boolean interactive) {
        label.setText("v" + UpdateChecker.CURRENT_VERSION + " (buscando...) ");
        Thread t = new Thread(new Runnable() {
            public void run() {
                runCheck(interactive);
            }
        }, "csv-download-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Cuerpo de la comprobacion (hilo aparte, nunca en el EDT). */
    private void runCheck(final boolean interactive) {
        String text;
        String tip;
        try {
            UpdateChecker.Release r = fetchCached(interactive);
            latest = r;
            if (r == null) {
                text = "v" + UpdateChecker.CURRENT_VERSION;
                tip = "Sin versiones publicadas en GitHub (o el repositorio es privado)";
            } else if (UpdateChecker.compare(r.version,
                    UpdateChecker.CURRENT_VERSION) > 0) {
                text = "v" + UpdateChecker.CURRENT_VERSION
                        + " - disponible v" + r.version;
                tip = "Descargar v" + r.version;
            } else {
                text = "v" + UpdateChecker.CURRENT_VERSION + " (al dia)";
                tip = "Ya tienes la ultima version (v" + r.version + ")";
            }
        } catch (Throwable ex) {
            text = "v" + UpdateChecker.CURRENT_VERSION;
            tip = "No se pudo consultar GitHub: " + ex.getMessage();
        }
        final String fText = text + " ";
        final String fTip = tip;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                label.setText(fText);
                download.setToolTipText(fTip);
                if (interactive && isShowing()) {
                    JOptionPane.showMessageDialog(UpdateBar.this, fTip,
                            "Actualizaciones",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
    }

    /**
     * Ultima release con cache compartida entre dialogos; force (clic del
     * usuario) ignora la cache. Un error no se guarda en la cache.
     */
    private static UpdateChecker.Release fetchCached(boolean force)
            throws Exception {
        synchronized (CACHE_LOCK) {
            long now = System.currentTimeMillis();
            if (!force && cachedAt > 0 && now - cachedAt < CACHE_MILLIS) {
                return cachedRelease;
            }
        }
        UpdateChecker.Release r = UpdateChecker.fetchLatest();
        synchronized (CACHE_LOCK) {
            cachedRelease = r;
            cachedAt = System.currentTimeMillis();
        }
        return r;
    }

    private void open(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Throwable ex) {
            JOptionPane.showInputDialog(this, "Abre esta direccion en el navegador:",
                    "Enlace", JOptionPane.INFORMATION_MESSAGE, null, null, url);
        }
    }

    /** "Acerca de": version instalada y enlaces al repo y a la descarga. */
    private void showAbout() {
        JEditorPane p = new JEditorPane("text/html",
                "<html><body style='font-family:sans-serif;width:340px'>"
                + "<b>Descarga de tablas a CSV para Oracle SQL Developer</b><br>"
                + "Version v" + UpdateChecker.CURRENT_VERSION + "<br><br>"
                + "Extension no oficial: no es un producto de Oracle.<br><br>"
                + "<a href='" + UpdateChecker.REPO_URL
                + "'>Codigo fuente en GitHub</a><br>"
                + "<a href='" + UpdateChecker.RELEASES_URL
                + "/latest'>Ultima version publicada</a><br>"
                + "<a href='" + UpdateChecker.RELEASES_URL
                + "/latest/download/" + UpdateChecker.JAR_NAME
                + "'>Descargar " + UpdateChecker.JAR_NAME + "</a>"
                + "</body></html>");
        p.setEditable(false);
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.addHyperlinkListener(new HyperlinkListener() {
            public void hyperlinkUpdate(HyperlinkEvent e) {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                        && e.getURL() != null) {
                    open(e.getURL().toString());
                }
            }
        });
        JOptionPane.showMessageDialog(this, p, "Acerca de",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private static JButton textButton(String text) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setMargin(new java.awt.Insets(2, 0, 2, 0));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    private static JButton iconButton(Icon icon) {
        JButton b = new JButton(icon);
        b.setFocusable(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setMargin(new java.awt.Insets(2, 2, 2, 2));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    private abstract static class BaseIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            g.setColor(c.getForeground());
            paint(g);
            g.dispose();
        }

        abstract void paint(Graphics2D g);
    }

    /** Logo de GitHub (mark-github de Octicons, 16x16) como Path2D. */
    private static final class GithubIcon extends BaseIcon {
        private static final String CURVES =
                "0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49"
                + "-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52"
                + "-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87"
                + ".51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36"
                + "-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2"
                + " .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27"
                + ".82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01"
                + " 1.93-.01 2.2 0 .21.15.46.55.38";
        private static final Path2D SHAPE = shape();

        private static Path2D shape() {
            Matcher m = Pattern.compile("-?(\\d+\\.?\\d*|\\.\\d+)").matcher(CURVES);
            java.util.List<Double> n = new java.util.ArrayList<>();
            while (m.find()) {
                n.add(Double.parseDouble(m.group()));
            }
            Path2D p = new Path2D.Double();
            p.moveTo(8, 0);
            p.curveTo(3.58, 0, 0, 3.58, 0, 8);
            double x = 0;
            double y = 8;
            for (int i = 0; i + 5 < n.size(); i += 6) {
                p.curveTo(x + n.get(i), y + n.get(i + 1), x + n.get(i + 2),
                        y + n.get(i + 3), x + n.get(i + 4), y + n.get(i + 5));
                x += n.get(i + 4);
                y += n.get(i + 5);
            }
            // arco A8.013 8.013 hasta (16,8), centro (8,8), sentido antihorario
            double a0 = Math.atan2(y - 8, x - 8);
            double k = 4.0 / 3 * Math.tan((a0 - 0) / 4) * 8;
            p.curveTo(x + k * Math.sin(a0), y - k * Math.cos(a0),
                    16, 8 + k, 16, 8);
            p.curveTo(16, 3.58, 12.42, 0, 8, 0);
            p.closePath();
            return p;
        }

        @Override
        void paint(Graphics2D g) {
            g.translate(1, 1);
            g.scale(18 / 16.0, 18 / 16.0);
            g.fill(SHAPE);
        }
    }

    private static final class DownloadIcon extends BaseIcon {
        @Override
        void paint(Graphics2D g) {
            g.setColor(new Color(30, 110, 200));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.drawLine(10, 2, 10, 12);
            g.drawLine(5, 8, 10, 13);
            g.drawLine(15, 8, 10, 13);
            g.drawLine(3, 17, 17, 17);
        }
    }
}
