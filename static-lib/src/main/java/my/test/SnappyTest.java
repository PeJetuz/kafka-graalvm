package my.test;

import com.github.luben.zstd.Zstd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

public final class SnappyTest {
    @Test
    public void staticLib() throws Exception {
        final String src = "Odin Dva Tri";
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final OutputStream os = new SnappyOutputStream(baos);
        os.write(src.getBytes("UTF-8"));
        os.flush();
        final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        final InputStream is = new SnappyInputStream(bais);
        final String result = new String(is.readAllBytes(), "UTF-8");
        os.close();
        is.close();
        MatcherAssert.assertThat(
            "Shold be equals",
            result,
            Matchers.equalTo(src)
        );
    }

    @Test
    public void zstd() throws Exception {
        final String src = "Odin Dva Tri";
        final byte[] compressed = Zstd.compress(src.getBytes("UTF-8"));
        final String desc = new String(Zstd.decompress(compressed, compressed.length), "UTF-8");
        MatcherAssert.assertThat(
            "Shold be equals",
            desc,
            Matchers.equalTo(src)
        );
    }
}
