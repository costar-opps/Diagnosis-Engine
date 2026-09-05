package org.example.diagnosis.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ProjectProfile {

    private Identity identity = new Identity();
    private Datasources datasources = new Datasources();
    private Knowledge knowledge = new Knowledge();
    private Business business = new Business();
    private Dependencies dependencies = new Dependencies();
    private Ops ops = new Ops();

    @Data
    public static class Identity {
        private String id;
        private String name;
        private String owner;
        private Integer port;
        private List<String> tags = new ArrayList<>();
    }

    @Data
    public static class Datasources {
        private LogSource logs = new LogSource();
        private List<MetricDef> metrics = new ArrayList<>();
        private CodeSource code = new CodeSource();
        private ChangeSource change = new ChangeSource();
    }

    @Data
    public static class LogSource {
        /** cls | file | es | demo */
        private String type = "demo";
        private String locator;
        private Map<String, String> fieldMap = new LinkedHashMap<>();
    }

    @Data
    public static class MetricDef {
        private String name;
        private String promQL;
        private Double threshold;
        /** upper | lower */
        private String thresholdDirection = "upper";
        private String unit;
        private String meaning;
        /** platform | project */
        private String scope = "project";
    }

    @Data
    public static class CodeSource {
        private String repoPath;
        private String mainBranch = "main";
        private String packagePrefix;
    }

    @Data
    public static class ChangeSource {
        /** demo | git | none */
        private String type = "demo";
        private String locator;
    }

    @Data
    public static class Knowledge {
        private String namespace;
        private List<String> docRoots = new ArrayList<>();
    }

    @Data
    public static class Business {
        private List<CriticalPath> criticalPaths = new ArrayList<>();
        private List<KnownFault> knownFaults = new ArrayList<>();
        private String extraPromptHint;
    }

    @Data
    public static class CriticalPath {
        private String name;
        private List<String> steps = new ArrayList<>();
    }

    @Data
    public static class KnownFault {
        private String pattern;
        private String hint;
        private String runbookRef;
    }

    @Data
    public static class Dependencies {
        private List<String> upstream = new ArrayList<>();
        private List<String> downstream = new ArrayList<>();
    }

    @Data
    public static class Ops {
        private Map<String, String> alertLabels = new LinkedHashMap<>();
        private Map<String, String> severityMap = new LinkedHashMap<>();
        private String oncall;
    }
}
