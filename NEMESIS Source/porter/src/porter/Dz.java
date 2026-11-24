package porter;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** mkdz.py: "NDZ1" crc32 size csize (little-endian), then raw deflate level 9, for `livepatch adddex` */
final class Dz {
    private Dz() {}

    static byte[] make(byte[] dex) {
        Deflater d = new Deflater(9, true);
        d.setInput(dex);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished())
            z.write(buf, 0, d.deflate(buf));
        d.end();
        CRC32 c = new CRC32();
        c.update(dex);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('N'); o.write('D'); o.write('Z'); o.write('1');
        le(o, (int) c.getValue());
        le(o, dex.length);
        le(o, z.size());
        byte[] zz = z.toByteArray();
        o.write(zz, 0, zz.length);
        return o.toByteArray();
    }

    private static void le(ByteArrayOutputStream o, int v) {
        for (int i = 0; i < 4; i++)
            o.write(v >>> (8 * i));
    }
}
