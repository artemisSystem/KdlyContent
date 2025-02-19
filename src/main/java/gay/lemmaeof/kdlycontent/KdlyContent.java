package gay.lemmaeof.kdlycontent;

import com.google.common.collect.ImmutableSet;
import com.unascribed.lib39.core.api.ModPostInitializer;
import dev.hbeck.kdl.objects.KDLDocument;
import dev.hbeck.kdl.objects.KDLNode;
import dev.hbeck.kdl.parse.KDLParser;
import gay.lemmaeof.kdlycontent.api.ContentType;
import gay.lemmaeof.kdlycontent.api.KdlyRegistries;
import gay.lemmaeof.kdlycontent.api.ParseException;
import gay.lemmaeof.kdlycontent.content.ContentItem;
import gay.lemmaeof.kdlycontent.content.ContentLoading;
import gay.lemmaeof.kdlycontent.init.KdlyContentTypes;
import gay.lemmaeof.kdlycontent.init.KdlyGenerators;
import net.minecraft.item.*;
import net.minecraft.util.Identifier;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.item.group.api.QuiltItemGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class KdlyContent implements ModPostInitializer {
	public static final String MODID = "kdlycontent";
	public static final Logger LOGGER = LoggerFactory.getLogger("KdlyContent");

	private static final KDLParser parser = new KDLParser();

	public static final ItemGroup GROUP = QuiltItemGroup.createWithIcon(new Identifier("kdlycontent", "generated"), () -> new ItemStack(Items.CRAFTING_TABLE));

	@Override
	public void onPostInitialize() {
		KdlyContentTypes.init();
		KdlyGenerators.init();
		QuiltLoader.getEntrypoints("kdlycontent:before", Runnable.class).forEach(Runnable::run);
		ImmutableSet<ContentItem> data = ContentLoading.getAll("kdlycontent.kdl");
		for (ContentItem item : data) {
			String namespace = item.getIdentifier().getNamespace();
			try {
				KDLDocument kdl = parser.parse(item.createInputStream());
				parseKdl(namespace, kdl);
			} catch (IOException | ParseException e) {
				throw new RuntimeException("Could not load KDL for file " + item.getIdentifier(), e);
			}
		}
		StringBuilder builder = new StringBuilder("Registered ");
		List<String> messages = new ArrayList<>();
		KdlyRegistries.CONTENT_TYPES.forEach(type -> type.getApplyMessage().ifPresent(messages::add));
		for (int i = 0; i < messages.size() - 1; i++) {
			builder.append(messages.get(i));
			if (messages.size() > 2) builder.append(", ");
		}
		if (messages.size() > 1) {
			builder.append("and ").append(messages.get(messages.size() - 1));
		}
		LOGGER.info(builder.toString());
		QuiltLoader.getEntrypoints("kdlycontent:after", Runnable.class).forEach(Runnable::run);
	}

	//TODO: template overrides and such
	//TODO: oh god this method is a nightmare
	protected void parseKdl(String namespace, KDLDocument kdl) {
		Map<ContentType, Map<Identifier, KDLNode>> templates = new HashMap<>();
		for (KDLNode node : kdl.getNodes()) {
			Identifier id = new Identifier(namespace, "anonymous");
			String typeName = toSnakeCase(node.getIdentifier());
			if (!typeName.contains(":")) typeName = "kdlycontent:" + typeName;
			Identifier typeId = new Identifier(typeName);
			if (KdlyRegistries.CONTENT_TYPES.containsId(typeId)) {
				ContentType type = KdlyRegistries.CONTENT_TYPES.get(typeId);
				if (node.getType().isPresent() && node.getType().get().equals("template")) {
					id = new Identifier(node.getArgs().get(0).getAsString().getValue());
					templates.computeIfAbsent(type, t -> new HashMap<>()).put(id, node);
				} else {
					if (type.needsIdentifier()) {
						String name = node.getArgs().get(0).getAsString().getValue();
						id = new Identifier(namespace, name);
					}
					if (node.getProps().containsKey("template")) {
						Identifier templateId = new Identifier(node.getProps().get("template").getAsString().getValue());
						if (templates.containsKey(type)) {
							Map<Identifier, KDLNode> typeTemplates = templates.get(type);
							if (typeTemplates.containsKey(templateId)) {
								KDLNode template = typeTemplates.get(templateId);
								type.generateFrom(id, template);
							} else {
								throw new ParseException(id, "No template named `" + templateId + "` for content type `" + node.getIdentifier() + "` (converted to `" + typeId + "`)");
							}
						} else {
							throw new ParseException(id, "No templates for content type `" + node.getIdentifier() + "` found (converted to `" + typeId + "`)");
						}
					} else {
						//TODO: multiple IDs for quick-instantiation
						type.generateFrom(id, node);
					}
				}
			} else {
				throw new ParseException(id, "Content type `" + node.getIdentifier() + "` not found (converted to `" + typeId + "`)");
			}
		}
	}

	protected String toSnakeCase(String original) {
		//this may be considered sliiiightly evil, my condolences
		String regex = "([a-z])([A-Z]+)";
		String replacement = "$1_$2";
		original = original.replaceAll(regex, replacement).toLowerCase();
		return original;
	}
}
