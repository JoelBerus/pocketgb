package com.joelbermudez.pocketgb.saf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proveedor de prueba que habla el protocolo de DocumentsContract sobre un directorio privado
 * del APK de test. Los ids de documento son rutas relativas: {@code root}, {@code root/Sub},
 * {@code root/Sub/Juego.gb}. Un archivo cuyo nombre empieza por {@code virtual-} se anuncia como
 * documento virtual. El test lo provisiona con {@link TestFixtures} (mediante {@code call}).
 *
 * <p>Admite escritura (modos {@code w}, {@code wt}, {@code rw}, {@code rwt}) y los métodos
 * {@code android:createDocument}, {@code android:renameDocument} y {@code android:deleteDocument}, con
 * controles para simular proveedores mal portados: {@code failWrite}, {@code failClose},
 * {@code noTruncateOnW}, {@code noTruncateAtAll}, {@code pipeFd}, {@code createRenames},
 * {@code readOnlyFlags}, {@code denyMidWrite} y {@code blockWrite}.
 *
 * <p>Está en Java a propósito: corre en el proceso del APK de test, que no incluye la librería
 * estándar de Kotlin (AGP la deja solo en el APK de la app).
 */
public class TestDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.joelbermudez.pocketgb.test.documents";
    public static final String ROOT_ID = "root";

    private static final String[] DEFAULT_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_LAST_MODIFIED,
    };

    private volatile boolean denied = false;
    // Modos de fallo de un proveedor mal portado (H3) y revocación parcial (A4-01).
    private volatile String deniedDir = null;
    private volatile String throwingDir = null;
    private volatile boolean omitSizeColumn = false;
    private volatile boolean omitMimeColumn = false;
    private volatile boolean textSize = false;
    private final java.util.Map<String, Long> declaredSizes = new java.util.concurrent.ConcurrentHashMap<>();
    // Escritura (A5): modos de fallo y de comportamiento anómalo.
    private volatile boolean failWrite = false;
    private volatile boolean failClose = false;
    private volatile boolean noTruncateOnW = false;
    private volatile boolean noTruncateAtAll = false;
    private volatile boolean pipeFd = false;
    private volatile boolean createRenames = false;
    private volatile boolean readOnlyFlags = false;
    private volatile boolean denyMidWrite = false;
    private volatile boolean omitMtime = false;
    private volatile CountDownLatch writeGate = null;
    private final AtomicInteger waitingWriters = new AtomicInteger();
    private final List<String> writeModes = Collections.synchronizedList(new ArrayList<>());

    private File base() {
        return new File(getContext().getCacheDir(), "saf-fixture");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        try {
            switch (method) {
                case "reset":
                    denied = false;
                    deniedDir = null;
                    throwingDir = null;
                    omitSizeColumn = false;
                    omitMimeColumn = false;
                    textSize = false;
                    declaredSizes.clear();
                    failWrite = false;
                    failClose = false;
                    noTruncateOnW = false;
                    noTruncateAtAll = false;
                    pipeFd = false;
                    createRenames = false;
                    readOnlyFlags = false;
                    denyMidWrite = false;
                    omitMtime = false;
                    releaseGate();
                    writeModes.clear();
                    deleteRecursively(base());
                    base().mkdirs();
                    break;
                case "put": {
                    File file = new File(base(), arg);
                    file.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(extras.getByteArray("bytes"));
                    }
                    if (extras.containsKey("mtime")) file.setLastModified(extras.getLong("mtime"));
                    break;
                }
                case "get": {
                    File file = new File(base(), arg);
                    result.putBoolean("exists", file.isFile());
                    if (file.isFile()) result.putByteArray("bytes", readAll(new FileInputStream(file)));
                    break;
                }
                case "setMtime":
                    new File(base(), arg).setLastModified(extras.getLong("ms"));
                    break;
                case "failWrite":
                    failWrite = extras.getBoolean("on");
                    break;
                case "failClose":
                    failClose = extras.getBoolean("on");
                    break;
                case "noTruncateOnW":
                    noTruncateOnW = extras.getBoolean("on");
                    break;
                case "noTruncateAtAll":
                    noTruncateAtAll = extras.getBoolean("on");
                    break;
                case "pipeFd":
                    pipeFd = extras.getBoolean("on");
                    break;
                case "createRenames":
                    createRenames = extras.getBoolean("on");
                    break;
                case "readOnlyFlags":
                    readOnlyFlags = extras.getBoolean("on");
                    break;
                case "denyMidWrite":
                    denyMidWrite = extras.getBoolean("on");
                    break;
                case "omitMtime":
                    omitMtime = extras.getBoolean("on");
                    break;
                case "blockWrite":
                    if (extras.getBoolean("on")) {
                        writeGate = new CountDownLatch(1);
                    } else {
                        releaseGate();
                    }
                    break;
                case "releaseWrite":
                    releaseGate();
                    break;
                case "waitingWriters":
                    result.putInt("count", waitingWriters.get());
                    break;
                case "writeModes":
                    result.putStringArrayList("modes", new ArrayList<>(writeModes));
                    break;
                case "android:createDocument":
                    return createDocument(extras);
                case "android:renameDocument":
                    return renameDocument(extras);
                case "android:deleteDocument":
                    return deleteDocument(extras);
                case "sparse": {
                    File file = new File(base(), arg);
                    file.getParentFile().mkdirs();
                    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                        raf.setLength(extras.getLong("size"));
                    }
                    break;
                }
                case "mkdir":
                    new File(base(), arg).mkdirs();
                    break;
                case "deleteAll":
                    deleteRecursively(base());
                    break;
                case "denyDir":
                    deniedDir = arg;
                    break;
                case "throwDir":
                    throwingDir = arg;
                    break;
                case "omitSize":
                    omitSizeColumn = extras.getBoolean("on");
                    break;
                case "omitMime":
                    omitMimeColumn = extras.getBoolean("on");
                    break;
                case "textSize":
                    textSize = extras.getBoolean("on");
                    break;
                case "declareSize":
                    declaredSizes.put(arg, extras.getLong("size"));
                    break;
                case "deny":
                    denied = extras.getBoolean("denied");
                    break;
                case "snapshot": {
                    ArrayList<String> files = new ArrayList<>();
                    collect(base(), files);
                    result.putStringArrayList("files", files);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Método desconocido: " + method);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        Parsed parsed = parse(uri);
        if (parsed == null) return null;
        if (parsed.children && parsed.documentId.equals(deniedDir)) {
            throw new SecurityException("Permiso revocado en la subcarpeta (simulado)");
        }
        if (parsed.children && parsed.documentId.equals(throwingDir)) {
            throw new IllegalStateException("Proveedor roto (simulado)");
        }
        String[] columns = projection != null ? projection : DEFAULT_PROJECTION;
        if (omitSizeColumn || omitMimeColumn) {
            ArrayList<String> kept = new ArrayList<>();
            for (String column : columns) {
                if (omitSizeColumn && Document.COLUMN_SIZE.equals(column)) continue;
                if (omitMimeColumn && Document.COLUMN_MIME_TYPE.equals(column)) continue;
                kept.add(column);
            }
            columns = kept.toArray(new String[0]);
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        // Igual que DocumentsProvider: un documento inexistente devuelve null, no una excepción.
        try {
            if (parsed.children) {
                File[] files = resolve(parsed.documentId).listFiles();
                if (files == null) return null;
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File child : files) addRow(cursor, parsed.documentId + "/" + child.getName(), child);
            } else {
                addRow(cursor, parsed.documentId, resolve(parsed.documentId));
            }
        } catch (FileNotFoundException missing) {
            return null;
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        Parsed parsed = parse(uri);
        if (parsed == null) throw new FileNotFoundException(uri.toString());
        if ("r".equals(mode)) {
            return ParcelFileDescriptor.open(resolve(parsed.documentId), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!mode.equals("w") && !mode.equals("wt") && !mode.equals("rw") && !mode.equals("rwt")) {
            throw new IllegalArgumentException("Modo no soportado: " + mode);
        }
        writeModes.add(mode);
        if (readOnlyFlags) throw new SecurityException("Proveedor de solo lectura (simulado)");
        if (failWrite) throw new FileNotFoundException("Fallo de escritura (simulado)");
        awaitWriteGate();
        File file = resolve(parsed.documentId);
        boolean truncate = mode.contains("t") || ("w".equals(mode) && !noTruncateOnW);
        if (noTruncateAtAll) truncate = false;
        try {
            if (truncate) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.setLength(0);
                }
            }
            ParcelFileDescriptor result;
            if (failClose || pipeFd) {
                result = openPipe(file);
            } else {
                int flags = mode.startsWith("rw") ? ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_WRITE_ONLY;
                result = ParcelFileDescriptor.open(file, flags);
            }
            // La revocación llega "a mitad": el cliente ya tiene el descriptor, pero la siguiente consulta falla.
            if (denyMidWrite) denied = true;
            return result;
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }

    /** Tubería hacia el archivo: sin fsync posible; con {@code failClose} el lector descarta los datos y reporta un error. */
    private ParcelFileDescriptor openPipe(final File file) throws IOException {
        final boolean discard = failClose;
        final ParcelFileDescriptor[] pipe = discard ? ParcelFileDescriptor.createReliablePipe() : ParcelFileDescriptor.createPipe();
        final ParcelFileDescriptor readSide = pipe[0];
        new Thread(() -> {
            try {
                byte[] data = readAll(new FileInputStream(readSide.getFileDescriptor()));
                if (discard) {
                    readSide.closeWithError("Fallo al cerrar (simulado)");
                    return;
                }
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.seek(0);
                    raf.write(data);
                }
                readSide.close();
            } catch (IOException ignored) {
            }
        }, "test-documents-pipe").start();
        return pipe[1];
    }

    private void awaitWriteGate() {
        CountDownLatch gate = writeGate;
        if (gate == null) return;
        waitingWriters.incrementAndGet();
        try {
            gate.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            waitingWriters.decrementAndGet();
        }
    }

    private void releaseGate() {
        CountDownLatch gate = writeGate;
        writeGate = null;
        if (gate != null) gate.countDown();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("deprecation")
    private Bundle createDocument(Bundle extras) throws IOException {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        if (readOnlyFlags) throw new SecurityException("Proveedor de solo lectura (simulado)");
        Uri parentUri = extras.getParcelable("uri");
        Parsed parent = parse(parentUri);
        if (parent == null) throw new FileNotFoundException(String.valueOf(parentUri));
        File dir = resolve(parent.documentId);
        String mime = extras.getString(Document.COLUMN_MIME_TYPE);
        String name = extras.getString(Document.COLUMN_DISPLAY_NAME);
        boolean isDirectory = Document.MIME_TYPE_DIR.equals(mime);
        // Un proveedor real puede añadir una extensión según el tipo MIME.
        if (createRenames && !isDirectory) name = name + ".bin";
        File created = new File(dir, name);
        int counter = 1;
        while (created.exists()) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            created = new File(dir, stem + " (" + counter++ + ")" + ext);
        }
        if (isDirectory ? !created.mkdirs() : !created.createNewFile()) throw new IOException("No se pudo crear " + created);
        Bundle result = new Bundle();
        result.putParcelable("uri", documentUriLike(parentUri, parent.documentId + "/" + created.getName()));
        return result;
    }

    @SuppressWarnings("deprecation")
    private Bundle renameDocument(Bundle extras) throws IOException {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        Uri uri = extras.getParcelable("uri");
        Parsed parsed = parse(uri);
        if (parsed == null) throw new FileNotFoundException(String.valueOf(uri));
        File file = resolve(parsed.documentId);
        File target = new File(file.getParentFile(), extras.getString(Document.COLUMN_DISPLAY_NAME));
        if (target.exists() || !file.renameTo(target)) throw new IOException("No se pudo renombrar a " + target);
        String parentId = parsed.documentId.substring(0, parsed.documentId.lastIndexOf('/'));
        Bundle result = new Bundle();
        result.putParcelable("uri", documentUriLike(uri, parentId + "/" + target.getName()));
        return result;
    }

    @SuppressWarnings("deprecation")
    private Bundle deleteDocument(Bundle extras) throws IOException {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        Uri uri = extras.getParcelable("uri");
        Parsed parsed = parse(uri);
        if (parsed == null) throw new FileNotFoundException(String.valueOf(uri));
        File file = resolve(parsed.documentId);
        if (file.getCanonicalFile().equals(base().getCanonicalFile())) throw new SecurityException("No se borra la raíz");
        deleteRecursively(file);
        return new Bundle();
    }

    /** URI de documento con el mismo árbol que {@code like}. */
    private static Uri documentUriLike(Uri like, String documentId) {
        List<String> segments = like.getPathSegments();
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, segments.get(1));
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static final class Parsed {
        final String documentId;
        final boolean children;

        Parsed(String documentId, boolean children) {
            this.documentId = documentId;
            this.children = children;
        }
    }

    /** {@code /tree/<id>/document/<id>[/children]}. */
    private static Parsed parse(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 4 || !"tree".equals(segments.get(0)) || !"document".equals(segments.get(2))) {
            return null;
        }
        return new Parsed(segments.get(3), segments.size() > 4 && "children".equals(segments.get(4)));
    }

    private File resolve(String documentId) throws FileNotFoundException {
        File root = base();
        File file = ROOT_ID.equals(documentId) ? root : new File(root, documentId.substring(ROOT_ID.length() + 1));
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath()) || !file.exists()) {
                throw new FileNotFoundException(documentId);
            }
        } catch (IOException error) {
            throw new FileNotFoundException(documentId);
        }
        return file;
    }

    private void addRow(MatrixCursor cursor, String documentId, File file) {
        boolean isDirectory = file.isDirectory();
        int flags = 0;
        if (file.getName().startsWith("virtual-")) {
            flags = Document.FLAG_VIRTUAL_DOCUMENT;
        } else if (!readOnlyFlags) {
            flags = isDirectory
                ? Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE
                : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID:
                    value = documentId;
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    value = ROOT_ID.equals(documentId) ? "Juegos de prueba" : file.getName();
                    break;
                case Document.COLUMN_MIME_TYPE:
                    value = isDirectory ? Document.MIME_TYPE_DIR : "application/octet-stream";
                    break;
                case Document.COLUMN_SIZE:
                    if (isDirectory) {
                        value = null;
                    } else {
                        Long declared = declaredSizes.get(documentId);
                        long size = declared != null ? declared : file.length();
                        value = textSize ? (Object) "no-es-un-numero" : (Object) size;
                    }
                    break;
                case Document.COLUMN_FLAGS:
                    value = flags;
                    break;
                case Document.COLUMN_LAST_MODIFIED:
                    value = omitMtime ? null : (Object) file.lastModified();
                    break;
                default:
                    break;
            }
            row.add(column, value);
        }
    }

    private static void collect(File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getPath));
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else {
                out.add(child.getPath() + ":" + child.length() + ":" + child.lastModified());
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
