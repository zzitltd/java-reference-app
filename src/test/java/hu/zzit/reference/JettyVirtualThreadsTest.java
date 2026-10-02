package hu.zzit.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JettyVirtualThreadsTest {

    @Autowired
    private WebServerApplicationContext context;

    @Test
    void requestsRunOnVirtualThreadsWithTheConfiguredConcurrencyCap() {
        var pool = ((JettyWebServer) context.getWebServer()).getServer().getThreadPool();
        assertThat(pool).isInstanceOf(VirtualThreadPool.class);
        assertThat(((VirtualThreadPool) pool).getMaxThreads()).isEqualTo(10_000);
    }
}
