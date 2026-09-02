package com.blanoir.moons.agent.launch;

import com.blanoir.moons.agent.AgentBranding;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Executable single-JAR launcher: discovers Minecraft and attaches this same JAR. */
public final class MoonsLauncher {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private MoonsLauncher() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        if (options.help) {
            printUsage();
            return;
        }
        if (options.list) {
            listProcesses().forEach(process -> System.out.println(process.label()));
            return;
        }
        if (options.listMachine) {
            listProcesses().stream()
                    .filter(MinecraftProcess::likelyMinecraft)
                    .forEach(process -> System.out.println(
                            "MOONS_PROCESS:" + process.pid() + ":" + encode(process.label())));
            return;
        }

        MinecraftProcess target = options.pid == null
                ? waitForTarget(options.wait)
                : findByPid(options.pid);
        if (target == null) {
            throw new IllegalStateException("No Minecraft JVM was found");
        }
        progress(options, 45, "Minecraft JVM selected");
        attach(target, options.pid == null && !options.machineProgress, options);
    }

    private static MinecraftProcess waitForTarget(boolean wait) throws InterruptedException {
        boolean announced = false;
        while (true) {
            List<MinecraftProcess> candidates = listProcesses().stream()
                    .filter(MinecraftProcess::likelyMinecraft)
                    .toList();
            if (candidates.size() == 1) return candidates.getFirst();
            if (candidates.size() > 1) return choose(candidates);
            if (!wait) return null;
            if (!announced) {
                System.out.println(AgentBranding.prefix() + " Waiting for a Minecraft JVM...");
                announced = true;
            }
            Thread.sleep(POLL_INTERVAL);
        }
    }

    private static MinecraftProcess findByPid(String pid) {
        return listProcesses().stream()
                .filter(process -> process.pid().equals(pid))
                .findFirst()
                // Some launchers hide their descriptor from jvmstat while the
                // Attach endpoint is still reachable. An explicit PID is an
                // instruction to try the endpoint directly, not to trust the list.
                .orElseGet(() -> new MinecraftProcess(pid, "<explicit pid>", true));
    }

    private static List<MinecraftProcess> listProcesses() {
        String ownPid = Long.toString(ProcessHandle.current().pid());
        List<MinecraftProcess> result = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            if (!descriptor.id().equals(ownPid)) {
                result.add(MinecraftProcess.from(descriptor));
            }
        }
        result.sort(Comparator.comparing(MinecraftProcess::pid));
        return result;
    }

    private static MinecraftProcess choose(List<MinecraftProcess> candidates) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(AgentBranding.prefix() + " Multiple Minecraft JVMs found; use --pid <pid>:");
            candidates.forEach(process -> System.out.println("  " + process.label()));
            throw new IllegalStateException("Target selection is required");
        }
        Object selection = JOptionPane.showInputDialog(
                null,
                "选择要注入的 Minecraft 进程",
                AgentBranding.name() + " Injector",
                JOptionPane.PLAIN_MESSAGE,
                null,
                candidates.stream().map(MinecraftProcess::label).toArray(String[]::new),
                candidates.getFirst().label()
        );
        if (selection == null) throw new IllegalStateException("Injection cancelled");
        return candidates.stream()
                .filter(process -> process.label().equals(selection.toString()))
                .findFirst()
                .orElseThrow();
    }

    private static void attach(
            MinecraftProcess target,
            boolean showSuccessDialog,
            Options options
    ) throws Exception {
        Path jar = ownJar();
        String configuredHome = System.getProperty("moons.home", "").trim();
        Path home = configuredHome.isEmpty()
                ? defaultHome()
                : Path.of(configuredHome).toAbsolutePath().normalize();
        String agentArguments = "jar64=" + encode(jar.toString())
                + ";home64=" + encode(home.toString())
                + ";name64=" + encode(AgentBranding.name());
        System.out.println(AgentBranding.prefix() + " Attaching " + jar.getFileName() + " to " + target.label());
        progress(options, 55, "Opening target JVM");
        VirtualMachine machine = VirtualMachine.attach(target.pid());
        try {
            progress(options, 70, "Target JVM connected");
            progress(options, 82, "Loading " + AgentBranding.name() + " Agent");
            machine.loadAgent(jar.toString(), agentArguments);
            progress(options, 95, "Agent initialization completed");
        } finally {
            machine.detach();
        }
        System.out.println(AgentBranding.prefix() + " Injection completed");
        progress(options, 100, "Injection completed");
        if (showSuccessDialog && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, "注入完成", AgentBranding.name() + " Injector", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static Path ownJar() throws URISyntaxException {
        return Path.of(MoonsLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
    }

    private static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".moons").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."))
                .resolve(".moons")
                .toAbsolutePath()
                .normalize();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void progress(Options options, int percentage, String message) {
        if (options.machineProgress) {
            System.out.println("MOONS_PROGRESS:" + percentage + ":" + encode(message));
            System.out.flush();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar moons.jar [--wait|--no-wait] [--pid <pid>] [--list]");
    }

    private static final class Options {
        private boolean wait = true;
        private boolean list;
        private boolean listMachine;
        private boolean machineProgress;
        private boolean help;
        private String pid;

        private static Options parse(String[] arguments) {
            Options options = new Options();
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--wait" -> options.wait = true;
                    case "--no-wait" -> options.wait = false;
                    case "--list" -> options.list = true;
                    case "--list-machine" -> options.listMachine = true;
                    case "--machine-progress" -> options.machineProgress = true;
                    case "--help", "-h" -> options.help = true;
                    case "--pid" -> {
                        if (++index >= arguments.length) {
                            throw new IllegalArgumentException("--pid requires a value");
                        }
                        options.pid = arguments[index];
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arguments[index]);
                }
            }
            return options;
        }
    }
}
