package my.runner;

import java.io.PrintWriter;
import my.test.SnappyTest;
import static org.junit.platform.engine.discovery.ClassNameFilter.includeClassNamePatterns;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

public class RunJUnit5Tests {
    private final SummaryGeneratingListener listener = new SummaryGeneratingListener();

    public static void main(final String[] args) throws Exception {
        RunJUnit5Tests runner = new RunJUnit5Tests();
        runner.runAll();

        TestExecutionSummary summary = runner.listener.getSummary();
        summary.printTo(new PrintWriter(System.out));

        runner = new RunJUnit5Tests();
        runner.runOne();

        summary = runner.listener.getSummary();
        summary.printTo(new PrintWriter(System.out));

        System.out.println("Try direct call");
        SnappyTest test = new SnappyTest();
        test.staticLib();
        System.out.println("Snappy call successfully");
        test.zstd();
        System.out.println("Zstd call successfully");
    }

    private void runOne() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(SnappyTest.class))
            .build();
        Launcher launcher = LauncherFactory.create();
        TestPlan testPlan = launcher.discover(request);
        launcher.registerTestExecutionListeners(this.listener);
        launcher.execute(request);
    }

    private void runAll() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectPackage("my.test"))
            .filters(includeClassNamePatterns(".*Test"))
            .build();
        Launcher launcher = LauncherFactory.create();
        TestPlan testPlan = launcher.discover(request);
        launcher.registerTestExecutionListeners(this.listener);
        launcher.execute(request);
    }
}
