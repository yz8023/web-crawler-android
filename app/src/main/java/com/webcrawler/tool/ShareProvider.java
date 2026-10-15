package com.webcrawler.tool;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件分享 Provider（代替 androidx FileProvider，避免引入依赖增大体积）。
 * 只读地暴露 zips 目录中的压缩包：content://&lt;包名&gt;.share/&lt;文件名&gt;
 */
public class ShareProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return null;
        }
        File f = new File(CrawlManager.zipDir(getContext()), name);
        return f.isFile() ? f : null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        if (f == null) return null;
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] cols = new String[projection.length];
        Object[] values = new Object[projection.length];
        int n = 0;
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols[n] = col;
                values[n++] = f.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols[n] = col;
                values[n++] = f.length();
            }
        }
        String[] outCols = new String[n];
        Object[] outValues = new Object[n];
        System.arraycopy(cols, 0, outCols, 0, n);
        System.arraycopy(values, 0, outValues, 0, n);
        MatrixCursor cursor = new MatrixCursor(outCols, 1);
        cursor.addRow(outValues);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/zip";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("只读");
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
