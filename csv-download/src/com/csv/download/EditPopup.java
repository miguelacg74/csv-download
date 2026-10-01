package com.csv.download;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

/**
 * Menu contextual de edicion (click derecho) para campos de texto:
 * Cortar, Copiar, Pegar y Seleccionar todo. Swing no lo incluye de
 * serie en los componentes de texto (los atajos de teclado si
 * funcionan) y se echa en falta al pegar rutas, consultas o
 * listados de tablas.
 *
 * Cada item actua directamente sobre su componente (sin depender del
 * foco) y se habilita/deshabilita justo antes de mostrar el menu:
 * Cortar y Pegar solo si el campo es editable, Cortar y Copiar solo
 * si hay seleccion, y Pegar solo si el portapapeles tiene texto.
 * En componentes de solo lectura quedan utiles Copiar y Seleccionar
 * todo (p.ej. los resumenes de resultados del controller).
 *
 * Swing puro: no usa APIs del IDE.
 */
public final class EditPopup {

    private EditPopup() {
    }

    /** Instala el menu contextual de edicion en el componente. */
    public static void install(final JTextComponent c) {
        final JMenuItem cut = new JMenuItem("Cortar");
        cut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X,
                InputEvent.CTRL_DOWN_MASK));
        cut.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                c.cut();
            }
        });
        final JMenuItem copy = new JMenuItem("Copiar");
        copy.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C,
                InputEvent.CTRL_DOWN_MASK));
        copy.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                c.copy();
            }
        });
        final JMenuItem paste = new JMenuItem("Pegar");
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V,
                InputEvent.CTRL_DOWN_MASK));
        paste.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                c.paste();
            }
        });
        final JMenuItem all = new JMenuItem("Seleccionar todo");
        all.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                InputEvent.CTRL_DOWN_MASK));
        all.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                c.selectAll();
            }
        });

        JPopupMenu menu = new JPopupMenu();
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(all);
        menu.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean writable = c.isEditable() && c.isEnabled();
                boolean selection = c.getSelectedText() != null;
                cut.setEnabled(writable && selection);
                copy.setEnabled(selection);
                paste.setEnabled(writable && clipboardHasText());
                all.setEnabled(c.getDocument().getLength() > 0);
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        c.setComponentPopupMenu(menu);
    }

    /** True si el portapapeles del sistema tiene texto para pegar. */
    private static boolean clipboardHasText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard()
                    .isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (Throwable e) {
            // Portapapeles ocupado o no disponible (entorno sin pantalla).
            return false;
        }
    }
}
