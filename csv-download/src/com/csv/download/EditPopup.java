package com.csv.download;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

/**
 * Menu contextual de edicion (click derecho) para campos de texto:
 * Deshacer, Rehacer, Cortar, Copiar, Pegar y Seleccionar todo. Swing no
 * lo incluye de serie en los componentes de texto (los atajos de
 * cortar/copiar/pegar si funcionan) y se echa en falta al pegar rutas,
 * consultas o listados de tablas. Deshacer tampoco existe de serie: se
 * anade un {@link UndoManager} por componente con Ctrl+Z / Ctrl+Y.
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

    /** Instala el menu contextual de edicion (con deshacer) en el componente. */
    public static void install(final JTextComponent c) {
        install(c, true);
    }

    /**
     * Instala el menu contextual; con {@code undo} anade ademas
     * Deshacer/Rehacer (Ctrl+Z, Ctrl+Y o Ctrl+Mayus+Z). Sin deshacer para
     * editores compartidos entre celdas de una tabla, donde el historial
     * mezclaria valores de celdas distintas.
     */
    public static void install(final JTextComponent c, boolean undo) {
        final UndoSupport history = undo ? new UndoSupport(c) : null;
        c.putClientProperty(UndoSupport.class, history);
        final JMenuItem undoItem = new JMenuItem("Deshacer");
        undoItem.setAccelerator(UNDO_KEY);
        undoItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                history.undo();
            }
        });
        final JMenuItem redoItem = new JMenuItem("Rehacer");
        redoItem.setAccelerator(REDO_KEY);
        redoItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                history.redo();
            }
        });
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
        if (history != null) {
            menu.add(undoItem);
            menu.add(redoItem);
            menu.addSeparator();
        }
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(all);
        menu.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean writable = c.isEditable() && c.isEnabled();
                boolean selection = c.getSelectedText() != null;
                if (history != null) {
                    history.closeGroup();
                    undoItem.setEnabled(writable && history.manager.canUndo());
                    redoItem.setEnabled(writable && history.manager.canRedo());
                }
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

    /**
     * Olvida el historial de deshacer del componente (p.ej. tras
     * rellenarlo por codigo, para que Ctrl+Z no borre el valor inicial).
     */
    public static void clearHistory(JTextComponent c) {
        Object history = c.getClientProperty(UndoSupport.class);
        if (history instanceof UndoSupport) {
            ((UndoSupport) history).closeGroup();
            ((UndoSupport) history).manager.discardAllEdits();
        }
    }

    private static final KeyStroke UNDO_KEY =
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke REDO_KEY =
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke REDO_ALT_KEY = KeyStroke.getKeyStroke(
            KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    /** Cambios mas seguidos que esto se deshacen juntos (una "racha"). */
    private static final long GROUP_MILLIS = 800;

    /**
     * Historial de deshacer de un componente. Agrupa en un solo paso los
     * cambios hechos en rafaga (teclear seguido, o el borrar+insertar de
     * un setText/pegar sobre seleccion) para no deshacer letra a letra.
     */
    private static final class UndoSupport implements UndoableEditListener {

        private final JTextComponent c;
        private final UndoManager manager = new UndoManager();
        private CompoundEdit group;
        private long last;

        UndoSupport(JTextComponent c) {
            this.c = c;
            manager.setLimit(500);
            c.getDocument().addUndoableEditListener(this);
            bind(UNDO_KEY, "csv-undo", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    undo();
                }
            });
            AbstractAction redo = new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    redo();
                }
            };
            bind(REDO_KEY, "csv-redo", redo);
            bind(REDO_ALT_KEY, "csv-redo", redo);
        }

        private void bind(KeyStroke key, String name, AbstractAction action) {
            c.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
            c.getActionMap().put(name, action);
        }

        public void undoableEditHappened(UndoableEditEvent e) {
            long now = System.currentTimeMillis();
            if (group == null || !group.isInProgress()
                    || now - last > GROUP_MILLIS) {
                closeGroup();
                group = new CompoundEdit();
                manager.addEdit(group);
            }
            group.addEdit(e.getEdit());
            last = now;
        }

        /** Cierra la racha en curso para que se pueda deshacer. */
        void closeGroup() {
            if (group != null && group.isInProgress()) {
                group.end();
            }
        }

        void undo() {
            closeGroup();
            if (c.isEditable() && c.isEnabled() && manager.canUndo()) {
                try {
                    manager.undo();
                } catch (CannotUndoException ignored) {
                    // historial inconsistente: no hay nada que deshacer
                }
            }
        }

        void redo() {
            closeGroup();
            if (c.isEditable() && c.isEnabled() && manager.canRedo()) {
                try {
                    manager.redo();
                } catch (CannotRedoException ignored) {
                    // historial inconsistente: no hay nada que rehacer
                }
            }
        }
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
