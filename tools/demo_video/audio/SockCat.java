import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 에뮬레이터 안 추상 소켓에 붙어 들어오는 바이트를 파일로 씁니다. scrcpy 서버가 보내는 차 안 소리를 받는 데 씁니다.
 * 처음 바이트가 온 기기 시각을 찍어 두어, compose.py가 소리를 앱 기록 시각에 맞춥니다.
 * 쓰기: CLASSPATH=sockcat.jar app_process / SockCat 소켓이름 저장할파일
 */
public class SockCat {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        String outPath = args[1];
        LocalSocket s;
        for (int i = 0; ; i++) {
            s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                break;
            } catch (IOException e) {
                s.close();
                if (i >= 200) throw e;
                Thread.sleep(100);
            }
        }
        System.out.println("connected " + System.currentTimeMillis());
        InputStream in = s.getInputStream();
        long total = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outPath))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (total == 0) System.out.println("first " + System.currentTimeMillis() + " " + n);
                out.write(buf, 0, n);
                out.flush();
                total += n;
            }
        }
        System.out.println("bytes " + total);
    }
}
