package com.csv.download;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Historial de acceso rapido a los presets: recuerda los ultimos
 * archivos de preset cargados/guardados (el mas reciente primero) y
 * las ultimas carpetas usadas (salida de CSV y carpeta de lote), para
 * no tener que volver a buscar la ruta cada vez.
 *
 * Se persiste con {@link Preferences} (JDK puro, sin ficheros propios):
 * en Windows acaba en el registro del usuario. Los fallos de
 * persistencia se ignoran: lo peor que pasa es que el historial solo
 * dure la sesion.
 *
 * Java puro: no usa APIs del IDE, se puede probar fuera de SQL
 * Developer con SelfTest.
 */
public final class PresetHistory {

    /** Maximo de presets recordados en el historial. */
    public static final int MAX_ENTRIES = 10;

    private static final String KEY_FILE = "file.";
    private static final String KEY_BATCH_DIR = "lastBatchDir";
    private static final String KEY_OUTPUT_DIR = "lastOutputDir";

    private final Preferences prefs;

    /** Historial del usuario, en un nodo propio del plugin. */
    public static PresetHistory userDefault() {
        return new PresetHistory(
                Preferences.userRoot().node("com/csv/download"));
    }

    /** Constructor con nodo explicito: permite aislarlo en pruebas. */
    public PresetHistory(Preferences prefs) {
        this.prefs = prefs;
    }

    /** Presets recientes, el ultimo usado primero. */
    public synchronized List<File> entries() {
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < MAX_ENTRIES; i++) {
            String path = prefs.get(KEY_FILE + i, null);
            if (path != null && !path.trim().isEmpty()) {
                files.add(new File(path));
            }
        }
        return files;
    }

    /**
     * Apunta el preset como el ultimo usado (si ya estaba, sube al
     * frente) y recorta la lista a {@link #MAX_ENTRIES}.
     */
    public synchronized void add(File file) {
        if (file == null) {
            return;
        }
        List<File> files = entries();
        files.remove(file.getAbsoluteFile());
        files.add(0, file.getAbsoluteFile());
        store(files);
    }

    /** Saca el preset del historial (p.ej. si el archivo ya no existe). */
    public synchronized void remove(File file) {
        if (file == null) {
            return;
        }
        List<File> files = entries();
        files.remove(file.getAbsoluteFile());
        store(files);
    }

    /** Olvida todos los presets recientes. */
    public synchronized void clear() {
        store(new ArrayList<File>());
    }

    private void store(List<File> files) {
        while (files.size() > MAX_ENTRIES) {
            files.remove(files.size() - 1);
        }
        try {
            for (int i = 0; i < MAX_ENTRIES; i++) {
                String key = KEY_FILE + i;
                if (i < files.size()) {
                    prefs.put(key, files.get(i).getAbsolutePath());
                } else {
                    prefs.remove(key);
                }
            }
            prefs.flush();
        } catch (Exception ignored) {
            // sin persistencia el historial solo dura la sesion
        }
    }

    /** Ultima carpeta de presets ejecutada en lote ("" si ninguna). */
    public String lastBatchDirectory() {
        return prefs.get(KEY_BATCH_DIR, "");
    }

    public void setLastBatchDirectory(File dir) {
        putPath(KEY_BATCH_DIR, dir);
    }

    /** Ultima carpeta de salida de CSV validada ("" si ninguna). */
    public String lastOutputDirectory() {
        return prefs.get(KEY_OUTPUT_DIR, "");
    }

    public void setLastOutputDirectory(File dir) {
        putPath(KEY_OUTPUT_DIR, dir);
    }

    private void putPath(String key, File dir) {
        try {
            if (dir == null) {
                prefs.remove(key);
            } else {
                prefs.put(key, dir.getAbsolutePath());
            }
            prefs.flush();
        } catch (Exception ignored) {
            // sin persistencia el historial solo dura la sesion
        }
    }
}
