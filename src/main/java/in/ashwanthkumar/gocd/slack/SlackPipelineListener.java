package in.ashwanthkumar.gocd.slack;

import in.ashwanthkumar.gocd.slack.jsonapi.MaterialRevision;
import in.ashwanthkumar.gocd.slack.jsonapi.Modification;
import in.ashwanthkumar.gocd.slack.jsonapi.Pipeline;
import in.ashwanthkumar.gocd.slack.jsonapi.Stage;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineRule;
import in.ashwanthkumar.gocd.slack.ruleset.PipelineStatus;
import in.ashwanthkumar.gocd.slack.ruleset.Rules;
import in.ashwanthkumar.slack.webhook.Slack;
import in.ashwanthkumar.slack.webhook.SlackAttachment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import com.thoughtworks.go.plugin.api.logging.Logger;
import in.ashwanthkumar.utils.collections.Lists;
import in.ashwanthkumar.utils.lang.StringUtils;

import static in.ashwanthkumar.utils.lang.StringUtils.startsWith;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.json.JSONObject;

public class SlackPipelineListener extends PipelineListener {
    public static final int DEFAULT_MAX_CHANGES_PER_MATERIAL_IN_SLACK = 5;
    private final Logger LOG = Logger.getLoggerFor(SlackPipelineListener.class);

    private final Slack slack;
	
	
	private final List<String> passedList = Arrays.asList("Деплой отгремел.", "Деплой окончен. Всем спасибо.");
	private final List<String> failedList = Arrays.asList("Деплой провален.", "Чуда не произошло.", "Всё пропало.");
	private final List<String> buildingList = Arrays.asList("Деплой начался");
	private final List<String> brokenList = Arrays.asList("Всё сломалось.");
	private final List<String> fixedList = Arrays.asList("Отбой деплойной тревоги.");
	private final List<String> cancelledList = Arrays.asList("Деплой отменён.");
	
    public SlackPipelineListener(Rules rules) {
        super(rules);
        slack = new Slack(rules.getWebHookUrl(), rules.getProxy());
        updateSlackChannel(rules.getSlackChannel());

        slack.displayName(rules.getSlackDisplayName())
                .icon(rules.getSlackUserIcon());
    }

    @Override
    public void onBuilding(PipelineRule rule, GoNotificationMessage message) throws Exception {
        updateSlackChannel(rule.getChannel());
        updateWebhookUrl(rule.getWebhookUrl());
        slack.push(slackAttachment(rule, message, PipelineStatus.BUILDING));
    }

    @Override
    public void onPassed(PipelineRule rule, GoNotificationMessage message) throws Exception {
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
			Stage stage = pickCurrentStage(details.stages, message);
			
			buildAttachment.addField(new SlackAttachment.Field("Pipeline", details.name, true));
			
			buildAttachment.addField(new SlackAttachment.Field("Triggered by", stage.approvedBy, true));
			
			if (rules.getDisplayConsoleLogLinks() && pipelineStatus != PipelineStatus.PASSED && pipelineStatus != PipelineStatus.BUILDING) {
				consoleLogLinks = createConsoleLogLinks(rules.getGoServerHost(), details, stage, pipelineStatus);
            }
        } catch (GoNotificationMessage.BuildDetailsNotFoundException | IOException | URISyntaxException e) {
            buildAttachment.text("Couldn't fetch build details.");
            LOG.warn("Couldn't fetch build details", e);
        }

        // Describe the root changes that made up this build.
        if (rules.getDisplayMaterialChanges()) {
            try {
                List<MaterialRevision> changes = message.fetchChanges(rules);
				for (MaterialRevision change : changes) {
					StringBuilder sb = new StringBuilder();
                    
                    for (Modification mod : change.modifications) {
						//LOG.info("Mod revision for material " + change.material + " is " + mod.revision);
						//LOG.info("Material type is " + change.material.type);
						//LOG.info("Material description " + change.material.description);
						
                        String url = change.modificationUrl(mod);
                        if (url != null) {
							// mod.revision for S3 bucket fetch-recovery-fw contains name of file, so we don't want to cut it
							if (!"S3".equals(mod.userName)) {
								sb.append("<").append(url).append("|").append(mod.revision.substring(0, 6)).append(">");
							} else {
								sb.append("<").append(url).append("|").append(mod.revision).append(">");
							}
                            sb.append(": ");
                        } else if (mod.revision != null) {
                            sb.append(mod.revision.substring(0, 6));
                            sb.append(": ");
						}
						
						String comment = null;
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
					
					String materialName = change.material.getName();					
					
					String fieldName = "Changes for " + (materialName == null ? change.material.description : materialName);
					buildAttachment.addField(new SlackAttachment.Field(fieldName, sb.toString(), false));
                }
            } catch (Exception e) {
                buildAttachment.addField(new SlackAttachment.Field("Changes", "(Couldn't fetch changes; see server log.)", true));
                LOG.warn("Couldn't fetch changes", e);
            }
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
			case FIXED:
				return fixedList.get(new Random().nextInt(fixedList.size()));
			case BUILDING:
				return buildingList.get(new Random().nextInt(buildingList.size()));
			case FAILED:
				return failedList.get(new Random().nextInt(failedList.size()));
			case PASSED:
				return passedList.get(new Random().nextInt(passedList.size()));
			case CANCELLED:
				return cancelledList.get(new Random().nextInt(cancelledList.size()));
			default:
				return "";
		}
	}
	
	private void updateSlackChannel(String slackChannel) {
		LOG.debug(String.format("Updating target slack channel to %s", slackChannel));
        // by default post it to where ever the hook is configured to do so
        if (startsWith(slackChannel, "#")) {
            slack.sendToChannel(slackChannel.substring(1));
        } else if (startsWith(slackChannel, "@")) {
            slack.sendToUser(slackChannel.substring(1));
        }
    }

    private void updateWebhookUrl(String webbookUrl) {
        LOG.debug(String.format("Updating target webhookUrl to %s", webbookUrl));
        // by default pick the global webhookUrl
        if (StringUtils.isNotEmpty(webbookUrl)) {
            slack.setWebhookUrl(webbookUrl);
        }
    }
}
