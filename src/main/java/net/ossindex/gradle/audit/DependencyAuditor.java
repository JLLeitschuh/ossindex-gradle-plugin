package net.ossindex.gradle.audit;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.ossindex.common.IPackageRequest;
import net.ossindex.common.OssIndexApi;
import net.ossindex.common.PackageCoordinate;
import net.ossindex.common.PackageDescriptor;
import net.ossindex.common.VulnerabilityDescriptor;
import net.ossindex.common.filter.IVulnerabilityFilter;
import net.ossindex.common.filter.VulnerabilityFilterFactory;
import net.ossindex.gradle.AuditExclusion;
import net.ossindex.gradle.AuditExtensions;
import net.ossindex.gradle.input.GradleArtifact;
import org.gradle.api.GradleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyAuditor
{
  private static final Logger logger = LoggerFactory.getLogger(DependencyAuditor.class);

  public static final boolean FILTERDBG = false;

  private final AuditExtensions config;

  private Map<PackageDescriptor, PackageDescriptor> parents = new HashMap<>();

  private IPackageRequest request;

  public DependencyAuditor(final AuditExtensions auditConfig,
                           Set<GradleArtifact> gradleArtifacts,
                           final List<Proxy> proxies)
  {
    this.config =  auditConfig;
    for (Proxy proxy : proxies) {
      logger.info("Using proxy: " + proxy);
      OssIndexApi.addProxy(proxy.getScheme(), proxy.getHost(), proxy.getPort(), proxy.getUser(), proxy.getPassword());
    }

    if (FILTERDBG) {
      System.err.println("FILTERDBG [" + this.hashCode() + "]: Create request");
    }

    request = OssIndexApi.createPackageRequest();
    configure();
    addArtifactsToAudit(gradleArtifacts);
  }

  private void configure() {
    if (config != null) {
      IVulnerabilityFilter filter = VulnerabilityFilterFactory.getInstance().createVulnerabilityFilter();
      Collection<AuditExclusion> exclusions = config.getExclusions();
      if (FILTERDBG) {
        if (exclusions != null) {
          System.err.println("FILTERDBG [" + this.hashCode() + "]: Adding exclusions " + exclusions.size());
        }
        else {
          System.err.println("FILTERDBG [" + this.hashCode() + "]: No exclusions");
        }
      }
      for (AuditExclusion exclusion : exclusions) {
        if (FILTERDBG) {
          if (exclusion.hasVid("366734") || exclusion.hasPackage("scot.disclosure:aps-unit-test-utils")) {
            System.err.println("FILTERDBG [" + this.hashCode() + "]: add exclusion " + exclusion);
          }
        }
        exclusion.apply(filter);
      }
      request.addVulnerabilityFilter(filter);
    }
  }

  public Collection<MavenPackageDescriptor> runAudit() {
    if (FILTERDBG) {
      System.err.println("FILTERDBG [" + this.hashCode() + "]: Running audit on request " + request.hashCode());
    }
    try {
      List<MavenPackageDescriptor> results = new LinkedList<>();
      Collection<PackageDescriptor> packages = request.run();
      for (PackageDescriptor pkg : packages) {
        MavenPackageDescriptor mvnPkg = new MavenPackageDescriptor(pkg);
        if (parents.containsKey(pkg)) {
          PackageDescriptor parent = parents.get(pkg);
          if (parent != null) {
            mvnPkg.setParent(new MavenIdWrapper(parent));
          }
        }
        if (mvnPkg.getVulnerabilityMatches() > 0) {
          if (FILTERDBG) {
            System.err
                .println("FILTERDBG [" + this.hashCode() + "]: FOUND " + mvnPkg.getVulnerabilityMatches() + " vulns");
            List<VulnerabilityDescriptor> vulns = mvnPkg.getVulnerabilities();
            for (VulnerabilityDescriptor vuln : vulns) {
              System.err.println("FILTERDBG [" + this.hashCode() + "]:   * vuln id " + vuln.getId());
            }
          }
          results.add(mvnPkg);
        }
      }
      return results;
    }
    catch (IOException e) {
      throw new GradleException("Error trying to get audit results", e);
    }
  }

  private void addArtifactsToAudit(Set<GradleArtifact> gradleArtifacts) {
    gradleArtifacts.forEach(this::addArtifact);
  }

  private void addArtifact(GradleArtifact gradleArtifact) {
    PackageCoordinate parentCoordinate = buildCoordinate(gradleArtifact);
    PackageDescriptor parent = request.add(Collections.singletonList(parentCoordinate));
    parents.put(parent, null);
    gradleArtifact.getAllChildren().forEach(c -> addPackageDependencies(parent, parentCoordinate, c));
  }

  private void addPackageDependencies(PackageDescriptor parent, PackageCoordinate parentCoordinate, GradleArtifact gradleArtifact) {
    PackageDescriptor pkgDep = new PackageDescriptor("maven", gradleArtifact.getGroup(), gradleArtifact.getName(),
        gradleArtifact.getVersion());
    if (!parents.containsKey(pkgDep)) {
      PackageCoordinate childCoordinate = buildCoordinate(gradleArtifact);
      pkgDep = request.add(Arrays.asList(new PackageCoordinate[] {parentCoordinate, childCoordinate}));
      parents.put(pkgDep, parent);
    }
  }

  private PackageCoordinate buildCoordinate(final GradleArtifact gradleArtifact) {
    PackageCoordinate coord = PackageCoordinate.newBuilder()
        .withFormat("maven")
        .withNamespace(gradleArtifact.getGroup())
        .withName(gradleArtifact.getName())
        .withVersion(gradleArtifact.getVersion())
        .build();
    return coord;
  }

  private String toString(PackageCoordinate pkg) {
    return pkg.getFormat() + ":" + pkg.getNamespace() + ":" + pkg.getName() + ":" + pkg.getVersion();
  }
}
