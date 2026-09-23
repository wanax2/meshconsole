package meshconsole.tools;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Meshtastic packet encryption: AES-CTR with nonce = packet id (LE64) + from node (LE32) + 32 zero bits. */
public final class MeshCrypto {
    private MeshCrypto() { }
    /** The well-known default key ("AQ==" / key index 1). */
    public static final byte[] DEFAULT_KEY = hex("d4f1bb3a20290759f0bcffabcf4e6901");

    public static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }

    /** Expands a 1-byte "simple" key (index into the default key with the last byte bumped) or returns 16/32-byte keys as-is. */
    public static byte[] expandKey(byte[] psk) {
        if (psk.length == 1) {
            if (psk[0] == 0) return new byte[0];
            byte[] k = DEFAULT_KEY.clone();
            k[15] = (byte) (k[15] + psk[0] - 1);
            return k;
        }
        return psk;
    }

    public static byte[] decrypt(byte[] key, int packetId, int from, byte[] data) throws Exception {
        if (key.length == 0) return data;
        byte[] nonce = new byte[16];
        long id = Integer.toUnsignedLong(packetId);
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (id >>> (8 * i));
        long f = Integer.toUnsignedLong(from);
        for (int i = 0; i < 4; i++) nonce[8 + i] = (byte) (f >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(nonce));
        return c.doFinal(data);
    }
}
