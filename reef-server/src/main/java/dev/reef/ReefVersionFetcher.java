package dev.reef;

import com.destroystokyo.paper.util.VersionFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.papermc.paper.ServerBuildInfo;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.apache.logging.log4j.LogManager;

/**
 * Version info and update check for /version and the startup log.
 *
 * <p>Paper's own fetcher looks the running build up in the PaperMC/Paper repository,
 * which never contains Reef builds, so it always reported "unknown version".
 * This one compares the running build's git commit with the latest commit on the
 * Reef repository configured in reef.yml (update-checker.repository / .branch),
 * using GitHub's public API. The repository has to be public for this to work.</p>
 */
public final class ReefVersionFetcher implements VersionFetcher {

    private static final ComponentLogger LOGGER = ComponentLogger.logger(LogManager.getRootLogger().getName());
    private static final Gson GSON = new Gson();

    private sealed interface Status {
        record UpToDate() implements Status {}
        record Behind(int commits) implements Status {}
        record Unknown(String reason) implements Status {}
    }

    @Override
    public long getCacheTime() {
        return 720000; // 12 minutes, so /version doesn't hit GitHub every time
    }

    @Override
    public Component getVersionMessage() {
        final Component header = Component.text(describe(), NamedTextColor.AQUA);
        final Status status = checkForUpdates();
        final Component body = switch (status) {
            case Status.UpToDate ignored -> Component.text("You are running the latest Reef build", NamedTextColor.GREEN);
            case Status.Behind behind -> Component.text("You are " + behind.commits() + " commit(s) behind the latest Reef build", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(Component.text("Download it at: ", NamedTextColor.YELLOW))
                .append(Component.text(downloadPage(), NamedTextColor.GOLD)
                    .hoverEvent(Component.text("Click to open", NamedTextColor.WHITE))
                    .clickEvent(ClickEvent.openUrl(downloadPage())));
            case Status.Unknown unknown -> Component.text(unknown.reason(), NamedTextColor.GRAY);
        };
        return Component.textOfChildren(header, Component.newline(), body);
    }

    /** Printed once at startup, in place of Paper's update check. */
    public static void printStartupMessage() {
        LOGGER.info(Component.text("*** " + describe() + " ***"));
        final Status status = checkForUpdates();
        switch (status) {
            case Status.UpToDate ignored -> LOGGER.info(Component.text("*** You are running the latest Reef build ***"));
            case Status.Behind behind -> {
                LOGGER.warn(Component.text("*** You are " + behind.commits() + " commit(s) behind the latest Reef build ***"));
                LOGGER.warn(Component.text("*** Download it at " + downloadPage() + " ***"));
            }
            case Status.Unknown unknown -> LOGGER.info(Component.text("*** " + unknown.reason() + " ***"));
        }
    }

    private static String describe() {
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        final StringBuilder builder = new StringBuilder("Running Reef for Minecraft ").append(info.minecraftVersionId());
        info.buildNumber().ifPresent(build -> builder.append(", build #").append(build));
        info.gitCommit().ifPresent(commit -> builder.append(" (").append(shortCommit(commit)).append(')'));
        return builder.append(", based on Paper").toString();
    }

    private static String downloadPage() {
        return "https://github.com/" + ReefConfig.updateCheckerRepository + "/actions";
    }

    private static String shortCommit(final String commit) {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private static Status checkForUpdates() {
        if (!ReefConfig.updateCheckerEnabled) {
            return new Status.Unknown("Update checks are turned off in reef.yml");
        }
        final Optional<String> commit = ServerBuildInfo.buildInfo().gitCommit();
        if (commit.isEmpty()) {
            return new Status.Unknown("This is a local development build, so it can't be checked for updates");
        }

        final String url = "https://api.github.com/repos/%s/compare/%s...%s".formatted(
            ReefConfig.updateCheckerRepository, ReefConfig.updateCheckerBranch, commit.get());
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Reef/" + ServerBuildInfo.buildInfo().minecraftVersionId());
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.connect();

            final int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // private repository, wrong name in reef.yml, or a commit that was never pushed
                return new Status.Unknown("Couldn't check for updates: " + ReefConfig.updateCheckerRepository
                    + " isn't public, or this build's commit isn't on GitHub");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return new Status.Unknown("Couldn't check for updates (GitHub returned " + code + ")");
            }

            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                final JsonObject json = GSON.fromJson(reader, JsonObject.class);
                final String status = json.get("status").getAsString();
                return switch (status) {
                    case "identical" -> new Status.UpToDate();
                    case "behind" -> new Status.Behind(json.get("behind_by").getAsInt());
                    // "ahead"/"diverged": this build has commits that aren't on the branch (e.g. a local test build)
                    default -> new Status.Unknown("This build isn't on the " + ReefConfig.updateCheckerBranch + " branch, so it can't be compared");
                };
            }
        } catch (final Exception exception) {
            return new Status.Unknown("Couldn't check for updates: " + exception.getClass().getSimpleName());
        }
    }
}
