package io.github.duckasteroid.gradle.versioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Rewrites {@code <dependency>} version elements in a generated Maven POM so that a project
 * dependency on another module in the same build publishes that module's last final release
 * version, not whatever decorated (typically {@code -SNAPSHOT}) build version {@code
 * project.version} happened to resolve to for the current HEAD.
 *
 * <p>Every project in a multi-project build computes its version at the same HEAD commit (see
 * {@link VersionResolver}). A module's own tag is handled correctly, but a project dependency on
 * a sibling module whose final tag points at an earlier commit still resolves that sibling's
 * {@code project.version}, which is decorated with {@code -SNAPSHOT} because HEAD isn't exactly on
 * that sibling's tag. Left alone, {@code maven-publish} writes that decorated version straight
 * into the POM - see <a href="https://github.com/duckAsteroid/gradle-versioning/issues/2">issue
 * #2</a>. Rewriting the XML text after POM generation (rather than changing {@code
 * project.version} itself, which must stay branch/SNAPSHOT-decorated for ordinary local builds) is
 * the only point where "the version actually used for this build" and "the version that should be
 * published" can differ.
 *
 * <p>Deliberately operates on a plain {@link Element} (not Gradle's {@code XmlProvider} or
 * Groovy's {@code Node}) so it has no Gradle-API dependency and can be unit tested directly against
 * a parsed POM fragment.
 */
final class ProjectDependencyVersionRewriter {

    private ProjectDependencyVersionRewriter() {
    }

    /**
     * @param pomProjectElement the root {@code <project>} element of a generated POM
     * @param releaseVersionsByGav {@code "groupId:artifactId"} to last-final-release-version, for
     *     every project in this build that applies {@code io.github.duckasteroid.version} (see
     *     {@code VersionPlugin}'s {@code project.ext.releaseVersion})
     */
    static void rewrite(Element pomProjectElement, Map<String, String> releaseVersionsByGav) {
        if (releaseVersionsByGav.isEmpty()) {
            return;
        }
        for (Element dependencies : directChildElements(pomProjectElement, "dependencies")) {
            for (Element dependency : directChildElements(dependencies, "dependency")) {
                String groupId = directChildText(dependency, "groupId");
                String artifactId = directChildText(dependency, "artifactId");
                if (groupId == null || artifactId == null) {
                    continue;
                }
                String releaseVersion = releaseVersionsByGav.get(groupId + ":" + artifactId);
                if (releaseVersion == null) {
                    continue;
                }
                Element version = directChildElement(dependency, "version");
                if (version != null) {
                    version.setTextContent(releaseVersion);
                }
            }
        }
    }

    /**
     * Only the immediate {@code <parent>/<tagName>} children, e.g. never {@code
     * dependencyManagement}'s nested {@code <dependencies>}.
     */
    private static List<Element> directChildElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && tagName.equals(child.getNodeName())) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static Element directChildElement(Element parent, String tagName) {
        List<Element> matches = directChildElements(parent, tagName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String directChildText(Element parent, String tagName) {
        Element child = directChildElement(parent, tagName);
        return child == null ? null : child.getTextContent();
    }
}
