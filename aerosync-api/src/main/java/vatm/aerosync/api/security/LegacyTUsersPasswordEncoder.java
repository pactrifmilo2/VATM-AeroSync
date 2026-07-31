package vatm.aerosync.api.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Compatibility encoder for the existing T_USERS.USERPASS format.
 *
 * <p>The legacy .NET application hashes UTF-16LE password bytes with MD5 and
 * formats the digest using BitConverter.ToString (uppercase hex pairs joined
 * by hyphens). This class exists only to verify existing accounts; new systems
 * should use an adaptive password hash.</p>
 */
public class LegacyTUsersPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        byte[] digest;
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            digest = md5.digest(
                    rawPassword.toString().getBytes(StandardCharsets.UTF_16LE));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }

        StringBuilder encoded = new StringBuilder(digest.length * 3 - 1);
        for (int index = 0; index < digest.length; index++) {
            if (index > 0) {
                encoded.append('-');
            }
            encoded.append(String.format(
                    Locale.ROOT,
                    "%02X",
                    Byte.toUnsignedInt(digest[index])));
        }
        return encoded.toString();
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        byte[] actual = encode(rawPassword).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = encodedPassword
                .trim()
                .toUpperCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }
}
