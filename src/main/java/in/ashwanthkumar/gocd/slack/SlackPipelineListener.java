package in.ashwanthkumar.gocd.slack;

import com.thoughtworks.go.plugin.api.logging.Logger;
import in.ashwanthkumar.gocd.slack.jsonapi.MaterialRevision;
import in.ashwanthkumar.gocd.slack.jsonapi.Modification;
import in.ashwanthkumar.gocd.slack.jsonapi.Pipeline;
import in.ashwanthkumar.gocd.slack.jsonapi.Stage;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineRule;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineStatus;
import in.ashwanthkumar.gocd.slack.ruleset.Rules;
import in.ashwanthkumar.slack.webhook.Slack;
import in.ashwanthkumar.slack.webhook.SlackAttachment;
import in.ashwanthkumar.utils.collections.Lists;
import in.ashwanthkumar.utils.lang.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static in.ashwanthkumar.utils.lang.StringUtils.startsWith;

public class SlackPipelineListener extends PipelineListener {
	private final Logger LOG = Logger.getLoggerFor(SlackPipelineListener.class);
	public static String lastCounter;
	private final Slack slack;

	private final static String TESTBED_PIPELINE = "deployTestbed";
	private final List<String> passedList = Arrays.asList("Деплой отгремел.", "Деплой окончен. Всем спасибо.");
	private final List<String> failedList = Arrays.asList("Деплой провален.", "Чуда не произошло.", "Всё пропало.");
	private final List<String> buildingList = Arrays.asList("Деплой начался.");
	private final List<String> brokenList = Arrays.asList("Всё сломалось.");
	private final List<String> cancelledList = Arrays.asList("Деплой отменён.");

	private final List<String> passedTestbedList = Arrays.asList("Деплой тестбеда окончен.", "Деплой тестбеда прошёл.");
	private final List<String> failedTestbedList = Arrays.asList("Деплой тестбеда провален.");
	private final List<String> buildingTestbedList = Arrays.asList("Деплой тестбеда начался.");
	private final List<String> brokenTestbedList = Arrays.asList("Деплой тестбеда сломался.");
	private final List<String> cancelledTestbedList = Arrays.asList("Деплой тестбеда отменён.");

	public SlackPipelineListener(Rules rules) {
		super(rules);

		slack = new Slack(rules.getWebHookUrl(), rules.getProxy());
		updateSlackChannel(rules.getSlackChannel());

		slack.displayName(rules.getSlackDisplayName()).icon(rules.getSlackUserIcon());
	}

	@Override
	public void onBuilding(PipelineRule rule, GoNotificationMessage message) throws Exception {
		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.BUILDING));
	}

	@Override
	public void onPassed(PipelineRule rule, GoNotificationMessage message) throws Exception {
		// Save last pipeline counter in case of success
		lastCounter = message.getPipelineCounter();
		LOG.info("lastCounter " + lastCounter);

		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.PASSED).color("good"));
	}

	@Override
	public void onFailed(PipelineRule rule, GoNotificationMessage message) throws Exception {
		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.FAILED).color("danger"));
	}

	@Override
	public void onBroken(PipelineRule rule, GoNotificationMessage message) throws Exception {
		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.BROKEN).color("danger"));
	}

	@Override
	public void onFixed(PipelineRule rule, GoNotificationMessage message) throws Exception {
		// Save last pipeline counter in case of success
		lastCounter = message.getPipelineCounter();
		LOG.info("lastCounter " + lastCounter);

		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.FIXED).color("good"));
	}

	@Override
	public void onCancelled(PipelineRule rule, GoNotificationMessage message) throws Exception {
		updateSlackChannel(rule.getChannel());
		updateWebhookUrl(rule.getWebhookUrl());
		slack.push(slackAttachment(rule, message, PipelineStatus.CANCELLED).color("warning"));
	}

	private SlackAttachment slackAttachment(PipelineRule rule, GoNotificationMessage message, PipelineStatus pipelineStatus) throws URISyntaxException {
		String title = String.format(verbFor(pipelineStatus));
		SlackAttachment buildAttachment = new SlackAttachment("")
				.fallback(title)
				.title(title);

		List<String> consoleLogLinks = new ArrayList<>();
		// Describe the build.
		try {
			Pipeline details = message.fetchDetails(rules);

			// Ugly way of adding custom lists with status for testbed
			if (details.name.equals(TESTBED_PIPELINE)) {
				title = String.format(verbForTestbed(pipelineStatus));

				buildAttachment.fallback(title).title(title);

				if (pipelineStatus == pipelineStatus.PASSED || pipelineStatus == pipelineStatus.FIXED) {
					buildAttachment.footer("Все занятые устройства нужно повторно аттачить к контейнерам.");
					buildAttachment.footerIcon("https://helpcenter.veeam.com/docs/vao/userguide/images/state_warning.png");
				}
			}

			LOG.info("message: " + message);
			Stage stage = pickCurrentStage(details.stages, message);

			buildAttachment.addField(new SlackAttachment.Field("Pipeline", details.name, true));

			// Reason for the first stage to trigger, not current
			buildAttachment.addField(new SlackAttachment.Field("Triggered by", details.stages[0].approvedBy, true));

			// Do not display console log links for all statuses except failed ones
			if (rules.getDisplayConsoleLogLinks() && pipelineStatus != PipelineStatus.PASSED && pipelineStatus != PipelineStatus.FIXED) {
				consoleLogLinks = createConsoleLogLinks(rules.getGoServerHost(), details, stage, pipelineStatus);
			}
		} catch (GoNotificationMessage.BuildDetailsNotFoundException e) {
			buildAttachment.text("Couldn't fetch build details.");
			LOG.warn("Couldn't fetch build details", e);
		} catch (IOException | URISyntaxException e) {
			buildAttachment.text(e.getMessage());
			LOG.warn(e.getMessage());
		}

		// Describe the root changes that made up this build.
		try {
			List<MaterialRevision> changes = message.fetchChanges(rules);
			for (MaterialRevision change : changes) {
				// Get material name
				String materialName = change.material.getName();

				// Do not show changes for ansible and angular-ndw
				// unless we find a way to show only whitelist changes
				if ("newndw".equals(materialName) || "ansible".equals(materialName)) {
					continue;
				}

				StringBuilder sb = new StringBuilder();
				for (Modification mod : change.modifications) {
					//LOG.info("Mod revision for material " + change.material + " is " + mod.revision);
					//LOG.info("Material type is " + change.material.type);
					//LOG.info("Material description " + change.material.description);

					String url = change.modificationUrl(mod);
					if (url != null) {
						// mod.revision for S3 bucket fetch-recovery-fw contains name of file, so we don't want to cut it
						if (!"S3".equals(mod.userName)) {
							sb.append("<").append(url).append("|").append(mod.revision, 0, 6).append(">");
						} else {
							sb.append("<").append(url).append("|").append(mod.revision).append(">");
						}
						sb.append(": ");
					} else if (mod.revision != null) {
						sb.append(mod.revision, 0, 6);
						sb.append(": ");
					}

					String comment;
					if (!"S3".equals(mod.userName)) {
						// For full comment use mod.comment();
						comment = mod.summarizeComment();
					} else {
						JSONObject j = new JSONObject(mod.comment);
						comment = j.getString("COMMENT");
					}
					if (comment != null) {
						sb.append(comment);
					}

					if (mod.userName != null && !"S3".equals(mod.userName)) {
						sb.append(" - ");
						sb.append(mod.userName);
					}
					sb.append("\n");
				}

				String fieldName = "Changes for " + (materialName == null ? change.material.description : materialName);
				buildAttachment.addField(new SlackAttachment.Field(fieldName, sb.toString(), false));
			}
		} catch (IOException | JSONException e) {
			buildAttachment.addField(new SlackAttachment.Field("Changes", "(Couldn't fetch changes; see server log.)", true));
			LOG.warn("Couldn't fetch changes", e);
		}


		if (!consoleLogLinks.isEmpty()) {
			String logLinks = Lists.mkString(consoleLogLinks, "", "", "\n");
			buildAttachment.addField(new SlackAttachment.Field("Console Logs", logLinks, true));
		}

		LOG.info("Pushing " + title + " notification to Slack");
		return buildAttachment;
	}

	private List<String> createConsoleLogLinks(String host, Pipeline pipeline, Stage stage, PipelineStatus pipelineStatus) throws URISyntaxException {
		List<String> consoleLinks = new ArrayList<>();
		for (String job : stage.jobNames()) {
			URI link;
			// We should be linking to Console Tab when the status is building,
			// while all others will be the console.log artifact.
			if (pipelineStatus == PipelineStatus.BUILDING) {
				link = new URI(String.format("%s/go/tab/build/detail/%s/%d/%s/%d/%s#tab-console", host, pipeline.name, pipeline.counter, stage.name, stage.counter, job));
			} else {
				link = new URI(String.format("%s/go/files/%s/%d/%s/%d/%s/cruise-output/console.log", host, pipeline.name, pipeline.counter, stage.name, stage.counter, job));
			}
			// TODO - May be it's only useful to show the failed job logs instead of all jobs?
			consoleLinks.add("<" + link.normalize().toASCIIString() + "| View " + job + " logs>");
		}
		return consoleLinks;
	}

	private Stage pickCurrentStage(Stage[] stages, GoNotificationMessage message) {
		for (Stage stage : stages) {
			if (message.getStageName().equals(stage.name)) {
				return stage;
			}
		}

		throw new IllegalArgumentException("The list of stages from the pipeline (" + message.getPipelineName() + ") doesn't have the active stage (" + message.getStageName() + ") for which we got the notification.");
	}

	private String verbFor(PipelineStatus pipelineStatus) {
		switch (pipelineStatus) {
			case BROKEN:
				return brokenList.get(new Random().nextInt(brokenList.size()));
			case BUILDING:
				return buildingList.get(new Random().nextInt(buildingList.size()));
			case FAILED:
				return failedList.get(new Random().nextInt(failedList.size()));
			case FIXED:
				/*
				 * Deploy often canceled manually, preventing slack users from seeing failure notification.
				 * This makes fixedList phrase look weird, so we just stick to passedList for now.
				 */
			case PASSED:
				// Save last counter for all dependent pipelines here?
				return passedList.get(new Random().nextInt(passedList.size()));
			case CANCELLED:
				return cancelledList.get(new Random().nextInt(cancelledList.size()));
			default:
				return "";
		}
	}

	private String verbForTestbed(PipelineStatus pipelineStatus) {
		switch (pipelineStatus) {
			case BROKEN:
				return brokenTestbedList.get(new Random().nextInt(brokenTestbedList.size()));
			case BUILDING:
				return buildingTestbedList.get(new Random().nextInt(buildingTestbedList.size()));
			case FAILED:
				return failedTestbedList.get(new Random().nextInt(failedTestbedList.size()));
			case FIXED:
				/*
				 * Deploy often canceled manually, preventing slack users from seeing failure notification.
				 * This makes fixedList phrase look weird, so we just stick to passedList for now.
				 */
			case PASSED:
				// Save last counter for all dependent pipelines here?
				return passedTestbedList.get(new Random().nextInt(passedTestbedList.size()));
			case CANCELLED:
				return cancelledTestbedList.get(new Random().nextInt(cancelledTestbedList.size()));
			default:
				return "";
		}
	}

	private void updateSlackChannel(String slackChannel) {
		LOG.info(String.format("Updating target slack channel to %s", slackChannel));
		// by default post it to where ever the hook is configured to do so
		if (startsWith(slackChannel, "#")) {
			slack.sendToChannel(slackChannel.substring(1));
		} else if (startsWith(slackChannel, "@")) {
			slack.sendToUser(slackChannel.substring(1));
		}
	}

	private void updateWebhookUrl(String webbookUrl) {
		LOG.info(String.format("Updating target webhookUrl to %s", webbookUrl));
		// by default pick the global webhookUrl
		if (StringUtils.isNotEmpty(webbookUrl)) {
			slack.setWebhookUrl(webbookUrl);
		}
	}
}
