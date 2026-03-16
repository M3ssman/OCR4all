package de.uniwue.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.uniwue.feature.ProcessHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LineSegmentationHelperTest {
    private Path projectRoot;
    private Path processingDir;

    @Before
    public void setUp() throws IOException {
        projectRoot = Files.createTempDirectory("lineseg-helper-test-");
        processingDir = Files.createDirectories(projectRoot.resolve("processing"));
    }

    @After
    public void tearDown() throws IOException {
        if (projectRoot == null || !Files.exists(projectRoot)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void executeBuildsDatasetAndCleansExistingTextLines() throws Exception {
        List<String> pageIds = Arrays.asList("0001", "0002");
        writeFile(processingDir.resolve("0001.xml"), "<PcGts><TextLine id=\"l1\">old</TextLine></PcGts>");
        writeFile(processingDir.resolve("0002.xml"), "<PcGts><TextLine id=\"l2\">old</TextLine></PcGts>");

        // Gray image exists for both pages, but only 0001 has a despeckled image.
        writeFile(processingDir.resolve("0001.nrm.png"), "img");
        writeFile(processingDir.resolve("0002.nrm.png"), "img");
        writeFile(processingDir.resolve("0001.desp.png"), "img");

        RecordingProcessHandler processHandler = new RecordingProcessHandler();
        LineSegmentationHelper helper = createHelperWithInjectedProcessHandler("Gray", processHandler);

        helper.execute(pageIds, Arrays.asList("--foo", "bar"));

        assertTrue(processHandler.fetchProcessConsole);
        assertEquals("ocr4all-helper-scripts", processHandler.programPath);
        assertFalse(processHandler.runInBackground);
        assertNotNull(processHandler.command);
        assertEquals("pagelineseg", processHandler.command.get(0));

        int datasetIndex = processHandler.command.indexOf("--dataset");
        assertTrue(datasetIndex >= 0);
        assertTrue(datasetIndex + 1 < processHandler.command.size());
        assertEquals("--foo", processHandler.command.get(datasetIndex + 2));
        assertEquals("bar", processHandler.command.get(datasetIndex + 3));

        File datasetFile = new File(processHandler.command.get(datasetIndex + 1));
        assertTrue(datasetFile.exists());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataset = mapper.readTree(datasetFile);
        assertEquals(2, dataset.size());

        assertEquals(processingDir.resolve("0001.desp.png").toString(), dataset.get(0).get(0).asText());
        assertEquals(processingDir.resolve("0001.xml").toString(), dataset.get(0).get(1).asText());

        assertEquals(processingDir.resolve("0002.nrm.png").toString(), dataset.get(1).get(0).asText());
        assertEquals(processingDir.resolve("0002.xml").toString(), dataset.get(1).get(1).asText());

        assertFalse(readFile(processingDir.resolve("0001.xml")).contains("<TextLine"));
        assertFalse(readFile(processingDir.resolve("0002.xml")).contains("<TextLine"));
    }

    @Test
    public void executeUsesDespeckledImageWhenPresent() throws Exception {
        List<String> pageIds = Arrays.asList("0100");
        writeFile(processingDir.resolve("0100.xml"), "<PcGts></PcGts>");
        writeFile(processingDir.resolve("0100.nrm.png"), "gray");
        writeFile(processingDir.resolve("0100.desp.png"), "despeckled");

        RecordingProcessHandler processHandler = new RecordingProcessHandler();
        LineSegmentationHelper helper = createHelperWithInjectedProcessHandler("Gray", processHandler);

        helper.execute(pageIds, new ArrayList<String>());

        int datasetIndex = processHandler.command.indexOf("--dataset");
        assertTrue(datasetIndex >= 0);

        File datasetFile = new File(processHandler.command.get(datasetIndex + 1));
        JsonNode dataset = new ObjectMapper().readTree(datasetFile);

        assertEquals(1, dataset.size());
        assertEquals(processingDir.resolve("0100.desp.png").toString(), dataset.get(0).get(0).asText());
        assertFalse(dataset.get(0).get(0).asText().endsWith("0100.nrm.png"));
    }

    @Test
    public void executeUsesProjectImageTypeWhenDespeckledIsAbsent() throws Exception {
        List<String> pageIds = Arrays.asList("0101");
        writeFile(processingDir.resolve("0101.xml"), "<PcGts></PcGts>");
        writeFile(processingDir.resolve("0101.nrm.png"), "gray");

        RecordingProcessHandler processHandler = new RecordingProcessHandler();
        LineSegmentationHelper helper = createHelperWithInjectedProcessHandler("Gray", processHandler);

        helper.execute(pageIds, new ArrayList<String>());

        int datasetIndex = processHandler.command.indexOf("--dataset");
        assertTrue(datasetIndex >= 0);

        File datasetFile = new File(processHandler.command.get(datasetIndex + 1));
        JsonNode dataset = new ObjectMapper().readTree(datasetFile);

        assertEquals(1, dataset.size());
        assertEquals(processingDir.resolve("0101.nrm.png").toString(), dataset.get(0).get(0).asText());
        assertFalse(dataset.get(0).get(0).asText().endsWith("0101.desp.png"));
    }

    @Test
    public void executeTracksTouchedXmlAsCompletedProgress() throws Exception {
        List<String> pageIds = Arrays.asList("0003");
        Path xml = processingDir.resolve("0003.xml");

        writeFile(xml, "<PcGts></PcGts>");
        writeFile(processingDir.resolve("0003.nrm.png"), "img");

        RecordingProcessHandler processHandler = new RecordingProcessHandler();
        processHandler.afterStartAction = () -> {
            File xmlFile = xml.toFile();
            xmlFile.setLastModified(System.currentTimeMillis() + 3000L);
        };

        LineSegmentationHelper helper = createHelperWithInjectedProcessHandler("Gray", processHandler);
        helper.execute(pageIds, new ArrayList<String>());

        // getProgress recalculates from stored baseline timestamps and should stay at 100.
        assertEquals(100, helper.getProgress());
    }

    private LineSegmentationHelper createHelperWithInjectedProcessHandler(String imageType, RecordingProcessHandler processHandler)
            throws Exception {
        // ProjectConfiguration concatenates paths directly, so we keep a trailing separator.
        String projectDir = projectRoot.toString() + File.separator;
        LineSegmentationHelper helper = new LineSegmentationHelper(projectDir, imageType);

        Field processHandlerField = LineSegmentationHelper.class.getDeclaredField("processHandler");
        processHandlerField.setAccessible(true);
        processHandlerField.set(helper, processHandler);

        return helper;
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static class RecordingProcessHandler extends ProcessHandler {
        private boolean fetchProcessConsole;
        private String programPath;
        private List<String> command;
        private boolean runInBackground;
        private Runnable afterStartAction;

        @Override
        public void setFetchProcessConsole(boolean fetchProcessConsole) {
            this.fetchProcessConsole = fetchProcessConsole;
        }

        @Override
        public void startProcess(String programPath, List<String> cmdArguments, boolean runInBackground)
                throws IOException {
            this.programPath = programPath;
            this.command = new ArrayList<String>(cmdArguments);
            this.runInBackground = runInBackground;

            if (afterStartAction != null) {
                afterStartAction.run();
            }
        }
    }
}