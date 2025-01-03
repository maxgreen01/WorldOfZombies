package me.mackblue.worldofzombies.modules.customblocks;

import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTContainer;
import de.tr7zw.nbtapi.NBTItem;
import me.mackblue.worldofzombies.WorldOfZombies;
import me.mackblue.worldofzombies.util.MultiBlockChangeWrap;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public class CustomBlockHelper {

    private final WorldOfZombies main;
    private final CustomBlockEvents customBlockEvents;
    private final Logger console;

    private FileConfiguration config;
    private FileConfiguration customBlockConfig;

    private int debug;

    //private Map<Player, MultiBlockChangeWrap[][][]> subChunkList = new HashMap<>();
    private double chunkReloadID;
    private List<String> recalculateChunkDisguisesBlacklist;
    private Map<String, String> idToDefinitionFilePath;
    private Map<String, YamlConfiguration> idToDefinitionFile;

    //constructor to initialize fields and load custom block config file
    public CustomBlockHelper(WorldOfZombies main, CustomBlockEvents customBlockEvents) {
        this.main = main;
        this.console = main.getLogger();
        this.customBlockEvents = customBlockEvents;

        reload();
    }

    public void reload() {
        main.createConfigs();
        config = main.getConfig();
        customBlockConfig = main.loadYamlFromFile(new File(main.getDataFolder(), "custom-blocks.yml"), false, false, debug, "");

        debug = customBlockConfig.getInt("Global.debug", 0);
        recalculateChunkDisguisesBlacklist = customBlockConfig.getStringList("Global.recalculate-chunk-disguises-blacklist");
        idToDefinitionFilePath = customBlockEvents.getIdToDefinitionFilePath();
        idToDefinitionFile = customBlockEvents.getIdToDefinitionFile();
        chunkReloadID = Math.random();
    }

    //adds all the blocks in the file for a chunk to their respective MultiBlockChange packets based on subChunk section
    public int loadLoggedBlocksInChunk(Player player, Chunk chunk) {
        boolean recalculateDisguises = true;
        int blockCount = 0;
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        World world = chunk.getWorld();
        String chunkString = world.getName() + ", " + chunkX + ", " + chunkZ;

        File chunkFolder = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + world.getName());
        if (!chunkFolder.exists()) {
            if (debug >= 4) {
                console.info(ChatColor.BLUE + "No custom blocks were loaded because the database folder for the world \"" + world.getName() + "\" does not exist");
            }
            return 0;
        }

        File file = new File(chunkFolder.getPath(), "chunk." + chunkX + "." + chunkZ + ".yml");
        YamlConfiguration logYaml = main.loadYamlFromFile(file, false, false, debug, ChatColor.BLUE + "There are no custom blocks in the chunk at: " + chunkString + "   (no file)");
        if (logYaml == null) {
            return 0;
        }

        if (main.removeEmpty(file, true, Arrays.asList("chunk-reload-id"), debug)) {
            if (debug >= 4) {
                console.info(ChatColor.BLUE + "There are no custom blocks in the chunk at: " + chunkString + "   (removed empty file)");
            }
            return 0;
        }

        Set<String> sections = logYaml.getKeys(false);

        if (!recalculateChunkDisguisesBlacklist.contains(world.getName())) {
            if (logYaml.contains("chunk-reload-id")) {
                if (logYaml.getDouble("chunk-reload-id") != chunkReloadID) {
                    //reload id is different, so disguised-block should be recalculated
                    logYaml.set("chunk-reload-id", chunkReloadID);
                } else {
                    //reload id is the same, so disguised-block should not be recalculated
                    recalculateDisguises = false;
                }
            } else {
                logYaml.set("chunk-reload-id", chunkReloadID);
            }
        }

        //if a top level key is a ConfigurationSection (by default they all will be), get and use data from its keys
        for (String sectionString : sections) {
            if (logYaml.isConfigurationSection(sectionString)) {
                ConfigurationSection subChunkSection = logYaml.getConfigurationSection(sectionString);
                int subChunkY = Integer.parseInt(sectionString.substring("subChunk".length()));
                String subChunkString = world.getName() + ", " + chunkX + ", " + subChunkY + ", " + chunkZ;

                Set<String> loggedBlocks = subChunkSection.getKeys(false);
                if (!loggedBlocks.isEmpty()) {
                    MultiBlockChangeWrap packet = new MultiBlockChangeWrap(chunkX, subChunkY, chunkZ);

                    //for each location in the subChunk, get (or recalculate and set) the child "disguised-data"
                    for (String loggedLocationString : loggedBlocks) {
                        if (subChunkSection.isConfigurationSection(loggedLocationString)) {
                            ConfigurationSection locationSection = subChunkSection.getConfigurationSection(loggedLocationString);
                            String[] locParts = loggedLocationString.split("_");
                            if (locParts.length < 3) {
                                console.severe(ChatColor.RED + "A custom block could not be loaded because the location key \"" + loggedLocationString + "\" in the subChunk at " + subChunkString + " is invalid");
                                continue;
                            }

                            Location loc = new Location(world, Double.parseDouble(locParts[0]), Double.parseDouble(locParts[1]), Double.parseDouble(locParts[2]));
                            String locString = world.getName() + ", " + locParts[0] + ", " + locParts[1] + ", " + locParts[2];
                            String id = locationSection.getString("id");
                            boolean secondBlock = locationSection.getBoolean("secondBlock", false);
                            String disguisedPathEnd = secondBlock ? "disguised-block2" : "disguised-block";

                            BlockData disguisedData;
                            if (recalculateDisguises) {
                                disguisedData = createCustomBlockData(world.getBlockAt(loc), id, true, secondBlock);
                                if (disguisedData != null) {
                                    locationSection.set("disguised-block", disguisedData.getAsString());
                                    if (debug >= 4) {
                                        console.info(ChatColor.BLUE + "The logged \"disguised-block\" for the block at " + locString + " was recalculated because the logged and plugin's chunk reload ID did not match or because this world is included in the \"recalculate-chunk-disguises-blacklist\"");
                                    }
                                }
                            } else {
                                String disguisedBlockString = locationSection.getString("disguised-block");
                                try {
                                    disguisedData = Bukkit.createBlockData(disguisedBlockString);
                                    if (debug >= 4) {
                                        console.info(ChatColor.BLUE + "The disguised BlockData for the block at " + locString + " was taken directly from the logged \"disguised-block\" because the logged and plugin's chunk reload ID matched");
                                    }
                                } catch (IllegalArgumentException e) {
                                    disguisedData = createCustomBlockData(world.getBlockAt(loc), id, true, secondBlock);
                                    if (debug >= 3) {
                                        console.warning(ChatColor.YELLOW + "The logged \"disguised-block\" for the block at " + locString + " was invalid or null, so it was recalculated");
                                    }
                                }
                            }

                            if (disguisedData == null) {
                                //no error message because error messages are handled in createBlockData()
                                continue;
                            }

                            if (disguisedData.getMaterial().equals(Material.AIR)) {
                                if (debug >= 4) {
                                    console.warning(ChatColor.YELLOW + "(THIS CAN PROBABLY BE IGNORED IF THIS IS DURING A CHUNK BEING LOADED) Did not load the \"" + id + "\" at " + locString + " because its source \"" + disguisedPathEnd + "\" is empty");
                                }
                            } else {
                                packet.addBlock(loc, disguisedData);
                                blockCount++;

                                if (debug >= 5) {
                                    console.info(ChatColor.GRAY + "Loaded the \"" + id + "\" at " + locString + " in the subChunk at " + subChunkString + " by " + player.getName());
                                }
                            }
                        }
                    }
                    packet.sendPacket(player);
                }
            }
        }

        try {
            logYaml.save(file);
        } catch (IOException e) {
            console.severe(ChatColor.RED + "Could not save the file for the chunk at " + chunkString);
        }

        return blockCount;
    }

    //wrapper method for calculating a custom block's disguised or actual BlockData, including sync-states, match-states, and force-actual-states
    public BlockData createCustomBlockData(Block block, String id, boolean disguised, boolean secondBlock) {
        createForcedBlockData(block, id, secondBlock);

        String originalBlockDataString = block.getBlockData().getAsString();
        BlockData matchedData = checkMatchStates(originalBlockDataString, id, disguised, secondBlock);
        if (matchedData != null) {
            return matchedData;
        }

        return createSyncedBlockData(originalBlockDataString, id, disguised, secondBlock);
    }

    //handles logic for placing custom blocks - adapted from BlockPlaceEvent for compatibility with BlockMultiPlaceEvent
    public void placeCustomBlock(Block block, ItemStack item, Player player, boolean secondBlock) {
        Chunk chunk = block.getChunk();
        World world = block.getWorld();

        NBTCompound wozItemComp = new NBTItem(item).getOrCreateCompound("WoZItem");
        String id = wozItemComp.getString("CustomItem");
        String sourceFilePath = idToDefinitionFilePath.get(id);

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String path = "subChunk" + (block.getY() >> 4) + "." + x + "_" + y + "_" + z + "." + "id";
        String chunkString = world.getName() + ", " + chunk.getX() + ", " + chunk.getZ();
        String locString = world.getName() + ", " + x + ", " + y + ", " + z;

        if (wozItemComp.getBoolean("IsCustomItem") && sourceFilePath != null) {

            YamlConfiguration sourceYaml = idToDefinitionFile.get(id);
            if (sourceYaml == null) {
                console.severe(ChatColor.RED + "An error occurred while trying to load the source file for the custom block \"" + id + "\"");
                return;
            }

            if (!sourceYaml.isConfigurationSection(id)) {
                console.severe(ChatColor.RED + "Could not log the custom block \"" + id + "\" because it does not exist in the file " + sourceFilePath);
                return;
            }

            String actualPathEnd = secondBlock ? "actual-block2" : "actual-block";
            String actual = sourceYaml.getString(id + ".block." + actualPathEnd);

            File file = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + world.getName(), "chunk." + chunk.getX() + "." + chunk.getZ() + ".yml");
            YamlConfiguration yaml = main.loadYamlFromFile(file, true, false, debug, "");
            if (yaml == null) {
                console.severe(ChatColor.RED + "An error occurred while loading or creating the database file for the chunk at " + chunk.getX() + ", " + chunk.getZ());
                return;
            }

            if (actual != null) {
                BlockData actualData = createCustomBlockData(block, id, false, secondBlock);
                if (actualData != null) {
                    block.setBlockData(actualData);
                }
            } else if (debug >= 3) {
                console.warning(ChatColor.YELLOW + "Did not change the server-side block for the custom block \"" + id + "\" at " + locString + " because its source \"" + actualPathEnd + "\" is empty");
            }

            yaml.set(path, id);
            if (secondBlock) {
                yaml.set(path.substring(0, path.length() - "id".length()) + "secondBlock", true);
            }
            if (debug >= 2) {
                console.info(ChatColor.AQUA + player.getName() + " added the custom block \"" + id + "\" at " + locString);
            }

            try {
                yaml.save(file);
                if (debug >= 2) {
                    console.info(ChatColor.DARK_AQUA + "Saved the file for the chunk at " + chunkString);
                }
            } catch (IOException e) {
                console.severe(ChatColor.RED + "Could not save the file for the chunk at " + chunkString);
            }
        } else if (wozItemComp.getBoolean("IsCustomItem") && sourceFilePath == null && debug >= 3) {
            console.warning(ChatColor.YELLOW + player.getName() + " placed " + block.getBlockData().getAsString() + " at " + locString + " which contains the tags \"IsCustomBlock:" + wozItemComp.getBoolean("IsCustomItem") + "\" and \"CustomBlock:" + id + "\", but \"" + id + "\" is not a valid custom block");
        }
    }

    //wrapper method for destroying a custom block: un-logs the block, drops custom items, plays custom break sound, and spawns custom break particles
    public void destroyLoggedBlock(Event event, boolean cancelEvent, Location loc, boolean dropItems, Player player, List<Item> originalDrops, boolean destroyEffects) {
        Block block = loc.getWorld().getBlockAt(loc);
        String id = (String) getLoggedObjectFromLocation(loc, "id");
        BlockData realBlockData = block.getBlockData();

        if (id != null) {
            if (cancelEvent && event instanceof Cancellable) {
                ((Cancellable) event).setCancelled(true);
            }

            //make sure the block is air
            if (!block.getType().isEmpty()) {
                block.setType(Material.AIR);
            }
            //custom particles and sounds
            if (destroyEffects) {
                YamlConfiguration yaml = idToDefinitionFile.get(id);
                if (yaml != null) {

                    try {
                        Object secondBlockObj = getLoggedObjectFromLocation(loc, "secondBlock", false);
                        boolean secondBlock = secondBlockObj != null && (boolean) secondBlockObj;
                        String particleDataString = secondBlock ? yaml.getString(id + ".block.destroy-particles2") : yaml.getString(id + ".block.destroy-particles");

                        if (particleDataString == null) {
                            particleDataString = (String) getLoggedObjectFromLocation(loc, "disguised-block");
                            if (particleDataString == null) {
                                particleDataString = realBlockData.getAsString();
                            }
                        }
                        BlockData particleData = Bukkit.createBlockData(particleDataString);


                        String soundString = yaml.getString(id + ".block.destroy-sound");
                        Sound sound = null;
                        if (!secondBlock) {
                            if (soundString == null) {
                                sound = particleData.getSoundGroup().getBreakSound();
                            } else {
                                sound = Sound.valueOf(soundString);
                            }
                        }

                        loc.getWorld().spawnParticle(Particle.BLOCK_CRACK, loc.getX() + 0.5, loc.getY() + 0.5, loc.getZ() + 0.5, 50, 0.2, 0.2, 0.2, particleData);
                        if (sound != null) {
                            loc.getWorld().playSound(loc, sound, 1.0f, 1.0f);
                        }
                    } catch (IllegalArgumentException e) {
                        console.severe(ChatColor.RED + "An error occurred while creating the break particles or break sound for the custom block \"" + id + "\"! Make sure its \"destroy-particles\" and \"destroy-sound\" are valid");
                    }
                } else {
                    console.severe(ChatColor.RED + "Could not load the break particles or sounds for the custom block \"" + id + "\" because its source file does not exist");
                }
            }

            unlogBlock(loc, player);

            //drop items
            if (dropItems) {
                spawnCustomBlockDrops(id, loc, originalDrops, player);
            }
        }
    }

    public BlockData createSyncedBlockData(String syncFromBlockDataString, String id, boolean disguise, boolean secondBlock) {
        return createSyncedBlockData(syncFromBlockDataString, id, null, null, disguise, secondBlock);
    }

    //syncs the specified states from syncFromBlockDataString to disguised-block, actual-block, or a parameter BlockData
    //null return value indicates an error, and a BlockData with a Material of AIR indicates that the source disguised-block or actual-block does not exist
    public BlockData createSyncedBlockData(String syncFromBlockDataString, String id, BlockData syncToBlockData, ConfigurationSection theSection, boolean disguise, boolean secondBlock) {
        ConfigurationSection section;
        if (theSection != null) {
            section = theSection;
        } else {
            YamlConfiguration sourceYaml = idToDefinitionFile.get(id);
            if (sourceYaml == null) {
                console.severe(ChatColor.RED + "The custom block \"" + id + "\" could not be loaded because its source file does not exist");
                return null;
            }

            if (!sourceYaml.isConfigurationSection(id + ".block")) {
                console.severe(ChatColor.RED + "The custom block \"" + id + "\" could not be loaded because its source \"block\" section is empty");
                return null;
            }
            section = sourceYaml.getConfigurationSection(id + ".block");
        }

        String dataPath = disguise ? "disguised-block" : "actual-block";
        String syncPath = disguise ? "disguised-sync-states" : "actual-sync-states";
        if (secondBlock) {
            dataPath += "2";
            syncPath += "2";
        }

        BlockData unsyncData;
        if (syncToBlockData != null) {
            unsyncData = syncToBlockData;
        } else if (section.contains(dataPath)) {
            try {
                unsyncData = Bukkit.createBlockData(section.getString(dataPath));
                if (unsyncData.getMaterial().isEmpty()) {
                    console.severe(ChatColor.RED + "The source \"" + dataPath + "\" for the custom block \"" + id + "\" cannot be a type of air");
                    return null;
                }
            } catch (IllegalArgumentException e) {
                console.severe(ChatColor.RED + "Could not load the BlockData for the custom block \"" + id + "\" because its source \"" + dataPath + "\" is invalid");
                return null;
            }
        } else {
            return Bukkit.createBlockData(Material.AIR);
        }

        //if the specific path with or without "2" does not exist
        if (!section.contains(syncPath)) {
            //if the specific path has "2", check again without the "2"
            if (syncPath.contains("2")) {
                syncPath = syncPath.substring(0, syncPath.length() - 1);

                //if the specific path without a "2" does not exist, check the base path with a "2"
                if (!section.contains(syncPath)) {
                    syncPath = "sync-states2";

                    //if the base path with a "2" does not exist, check the base path
                    if (!section.contains(syncPath)) {
                        syncPath = "sync-states";
                        if (!section.contains(syncPath)) {
                            return unsyncData;
                        }
                    }
                }
            } else {
                //if the specific path without a "2" doesn't exist, check the base path
                syncPath = "sync-states";
                if (!section.contains(syncPath)) {
                    return unsyncData;
                }
            }
        }

        List<String> syncList = section.getStringList(syncPath);
        String syncString = unsyncData.getMaterial().toString().toLowerCase() + "[";

        for (String state : syncList) {
            if (syncFromBlockDataString.contains(state)) {
                String afterState = syncFromBlockDataString.substring(syncFromBlockDataString.indexOf(state));
                int stateEnd = afterState.indexOf("]");
                if (afterState.contains(",")) {
                    if (afterState.indexOf(",") < stateEnd) {
                        stateEnd = afterState.indexOf(",");
                    }
                }
                syncString += afterState.substring(0, stateEnd) + ",";
            }
        }

        if (syncString.contains(",")) {
            syncString = syncString.substring(0, syncString.lastIndexOf(",")) + "]";
        } else {
            syncString = syncString.substring(0, syncString.length() - 1);
        }

        try {
            BlockData syncData = Bukkit.createBlockData(syncString);
            return unsyncData.merge(syncData);
        } catch (IllegalArgumentException e) {
            console.severe(ChatColor.RED + "Could not sync the states of the custom block \"" + id + "\" because its source \"" + dataPath + "\" is incompatible with one or more tags of the server-side block. " + e.getMessage());
            return unsyncData;
        }
    }

    //checks the disguised or actual match-states section and returns null if no conditions are met or the disguised-block inside the first matching state section
    public BlockData checkMatchStates(String originalBlockDataString, String id, boolean disguised, boolean secondBlock) {
        YamlConfiguration yaml = idToDefinitionFile.get(id);
        String path = disguised ? "disguised-match-states" : "actual-match-states";
        if (yaml != null && yaml.isConfigurationSection(id + ".block." + path)) {
            ConfigurationSection matchStatesSection = yaml.getConfigurationSection(id + ".block." + path);
            Set<String> states = matchStatesSection.getKeys(false);

            for (String state : states) {
                if (matchStatesSection.isConfigurationSection(state)) {
                    BlockData data = checkMatchStatesSection(originalBlockDataString, id, matchStatesSection.getConfigurationSection(state), disguised, secondBlock);
                    if (data != null) {
                        return data;
                    }
                }
            }
        }

        return null;
    }

    //recursive element of checkMatchStates() to actually check the states
    public BlockData checkMatchStatesSection(String originalBlockDataString, String id, ConfigurationSection section, boolean disguised, boolean secondBlock) {
        if (section.contains("state")) {
            String matchKey = section.getName();
            //removes extra digits from the end of a state name
            while (Character.isDigit(matchKey.charAt(matchKey.length() - 1))) {
                matchKey = matchKey.substring(0, matchKey.length() - 1);
            }

            String matchValue = section.get("state").toString();

            if (originalBlockDataString.contains(matchKey)) {
                String afterState = originalBlockDataString.substring(originalBlockDataString.indexOf(matchKey));
                int stateEnd = afterState.indexOf("]");
                if (afterState.contains(",")) {
                    if (afterState.indexOf(",") < stateEnd) {
                        stateEnd = afterState.indexOf(",");
                    }
                }

                String value = afterState.substring((matchKey + "=").length(), stateEnd);
                if (value.equalsIgnoreCase(matchValue)) {
                    String pathStart = disguised ? "disguised" : "actual";
                    String disguisePath = pathStart + "-block";
                    if (secondBlock && section.contains(disguisePath + "2")) {
                        disguisePath += "2";
                    }

                    if (section.contains(disguisePath)) {
                        try {
                            BlockData unsyncData = Bukkit.createBlockData(section.getString(disguisePath));
                            return createSyncedBlockData(originalBlockDataString, id, unsyncData, section, true, secondBlock);
                        } catch (IllegalArgumentException e) {
                            console.severe(ChatColor.RED + "Could not load the disguised BlockData in the \"" + pathStart + "-match-states\" section of the custom block \"" + id + "\" because the BlockData at \"" + section.getCurrentPath() + "." + disguisePath + "\" is invalid");
                            return null;
                        }
                    } else {
                        for (String state : section.getKeys(false)) {
                            if (section.isConfigurationSection(state)) {
                                BlockData data = checkMatchStatesSection(originalBlockDataString, id, section.getConfigurationSection(state), disguised, secondBlock);
                                if (data != null) {
                                    return data;
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    //sets the states of the actual block
    public BlockData createForcedBlockData(Block block, String id, boolean secondBlock) {
        YamlConfiguration yaml = idToDefinitionFile.get(id);

        if (yaml != null) {
            String blockDataString = block.getBlockData().getAsString();
            String path = "force-actual-states";
            if (secondBlock && yaml.contains(path + "2")) {
                path += "2";
            }

            if (yaml.isConfigurationSection(id + ".block." + path)) {
                ConfigurationSection section = yaml.getConfigurationSection(id + ".block." + path);
                Set<String> states = section.getKeys(false);

                int modified = 0;
                StringBuilder forcedBlockDataStringBuilder = new StringBuilder(blockDataString);
                for (String forceState : states) {
                    String forceValue = section.getString(forceState);

                    if (forcedBlockDataStringBuilder.indexOf(forceState) > 0) {
                        //if the original string contains this state, get the full state
                        String fullState = forcedBlockDataStringBuilder.substring(forcedBlockDataStringBuilder.indexOf(forceState));
                        int localStateEnd = fullState.indexOf("]") + 1;
                        if (fullState.contains(",")) {
                            if (fullState.indexOf(",") < localStateEnd) {
                                localStateEnd = fullState.indexOf(",") + 1;
                                fullState = fullState.substring(0, localStateEnd);
                            }
                        }

                        String value = fullState.substring((forceState + "=").length(), localStateEnd - 1);
                        //if the value is already set to the forced value, do nothing
                        if (!value.equalsIgnoreCase(forceValue)) {
                            //if the original value is different from the forced value, remove the original value and add the new one to the end of the string
                            modified++;
                            int stateStart = forcedBlockDataStringBuilder.indexOf(fullState);
                            forcedBlockDataStringBuilder.delete(stateStart, stateStart + fullState.length());

                            if (forcedBlockDataStringBuilder.substring(forcedBlockDataStringBuilder.length() - 1).equals("]")) {
                                forcedBlockDataStringBuilder.setCharAt(forcedBlockDataStringBuilder.length() - 1, ',');
                            }
                            forcedBlockDataStringBuilder.append(forceState).append("=").append(forceValue).append(",");
                        }
                    } else {
                        //if the original string doesn't contain the forced state, remove the ending bracket and then add the state to the end of the string
                        modified++;
                        if (forcedBlockDataStringBuilder.substring(forcedBlockDataStringBuilder.length() - 1).equals("]")) {
                            forcedBlockDataStringBuilder.setCharAt(forcedBlockDataStringBuilder.length() - 1, ',');
                        }

                        forcedBlockDataStringBuilder.append(forceState).append("=").append(forceValue).append(",");
                    }

                    if (forcedBlockDataStringBuilder.substring(forcedBlockDataStringBuilder.length() - 1).equals(",")) {
                        forcedBlockDataStringBuilder.setCharAt(forcedBlockDataStringBuilder.length() - 1, ']');
                    }
                }

                if (modified > 0) {
                    try {
                        BlockData forcedBlockData = Bukkit.createBlockData(forcedBlockDataStringBuilder.toString());
                        block.setBlockData(forcedBlockData);
                        if (debug >= 4) {
                            console.info(ChatColor.BLUE + "Successfully modified " + modified + " states from \"" + path + "\" to the custom block \"" + id + "\"");
                        }
                        return forcedBlockData;
                    } catch (IllegalArgumentException e) {
                        console.severe(ChatColor.RED + "An error occurred while creating the BlockData for the \"" + path + "\" of the custom block \"" + id + "\"! Make sure the state names and values are valid");
                    }
                }
            }
        }

        return null;
    }

    public Object getLoggedObjectFromLocation(Location loc, String path) {
        return getLoggedObjectFromLocation(loc, path, null);
    }

    //gets a logged string from a location
    public Object getLoggedObjectFromLocation(Location loc, String path, Object def) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int subChunkY = y >> 4;

        File file = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + world.getName(), "chunk." + chunkX + "." + chunkZ + ".yml");
        YamlConfiguration yaml = main.loadYamlFromFile(file, false, false, debug, "");

        String basePath = "subChunk" + subChunkY + "." + x + "_" + y + "_" + z;
        //String locString = world.getName() + ", " + x + ", " + y + ", " + z;
        if (yaml != null) {
            if (def != null) {
                return yaml.get(basePath + "." + path, def);
            } else {
                return yaml.get(basePath + "." + path);
            }
        }
        return null;
    }

    //sets a value in a log file from a location
    public void setLoggedInfoAtLocation(Location loc, String path, Object value) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int subChunkY = y >> 4;
        String chunkString = world.getName() + ", " + chunkX + ", " + chunkZ;

        File file = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + world.getName(), "chunk." + chunkX + "." + chunkZ + ".yml");
        YamlConfiguration yaml = main.loadYamlFromFile(file, false, false, debug, "");
        yaml.set("subChunk" + subChunkY + "." + x + "_" + y + "_" + z + "." + path, value);
        try {
            yaml.save(file);
        } catch (IOException e) {
            console.severe(ChatColor.RED + "Could not save the file for the chunk at " + chunkString);
        }
    }

    //un-logs a block if the block is air, or returns the newly created disguised BlockData for a block
    public BlockData unLogBlockOrCreateDisguisedBlockData(Location loc, String id, boolean secondBlock) {
        Block block = loc.getWorld().getBlockAt(loc);

        if (block.getType().isEmpty()) {
            console.info(ChatColor.DARK_PURPLE + "PACKET HANDLER unlogging block at " + loc);
            unlogBlock(loc, null);
        } else {
            BlockData data = createCustomBlockData(block, id, true, secondBlock);

            if (data == null) {
                return null;
            }

            if (data.getMaterial().equals(Material.AIR)) {
                return Bukkit.createBlockData(Material.AIR);
            } else {
                return data;
            }
        }
        return null;
    }

    //if a location is logged in a file, it will be removed from the file
    public void unlogBlock(Location loc, Player player) {
        File file = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + loc.getWorld().getName(), "chunk." + loc.getChunk().getX() + "." + loc.getChunk().getZ() + ".yml");

        String chunkString = loc.getWorld().getName() + ", " + loc.getChunk().getX() + ", " + loc.getChunk().getZ();
        String locString = loc.getWorld().getName() + ", " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String path = "subChunk" + (loc.getBlockY() >> 4) + "." + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
            String id = yaml.getString(path + ".id");

            if (yaml.contains(path)) {
                yaml.set(path, null);
                if (debug >= 2) {
                    if (player != null) {
                        console.info(ChatColor.AQUA + player.getName() + " un-logged the custom block \"" + id + "\" at " + locString);
                    } else {
                        console.info(ChatColor.AQUA + "The custom block \"" + id + "\" at " + locString + " was un-logged");
                    }
                }

                try {
                    yaml.save(file);
                    if (debug >= 2) {
                        console.info(ChatColor.DARK_AQUA + "Saved the file for the chunk at " + chunkString);
                    }
                } catch (IOException e) {
                    console.severe(ChatColor.RED + "Could not save the file for the chunk at " + chunkString);
                }
            } else if (debug >= 2) {
                console.severe(ChatColor.RED + "Did not un-log the block at " + locString + " because it does not exist in the file " + file.getPath());
            }
        } else if (debug >= 2) {
            console.info(ChatColor.BLUE + "Did not un-log the block at " + locString + " because its chunk file does not exist");
        }
    }

    //drops items and/or xp specified in the block.drops section of a custom block definition
    public void spawnCustomBlockDrops(String id, Location loc, List<Item> originalDrops, Player player) {
        List<ItemStack> newDrops = new ArrayList<>();
        String path = idToDefinitionFilePath.get(id);
        YamlConfiguration yaml = idToDefinitionFile.get(id);
        if (yaml == null) {
            console.severe(ChatColor.RED + "The drops for the custom block \"" + id + "\" could not be loaded because the source file " + path + " does not exist");
            return;
        }

        if (yaml.isConfigurationSection(id)) {
            ConfigurationSection idSection = yaml.getConfigurationSection(id);
            if (idSection.isConfigurationSection("block.drops")) {
                ConfigurationSection parentDropsSection = idSection.getConfigurationSection("block.drops");

                if (parentDropsSection.getBoolean("enabled", true)) {
                    Set<String> drops = parentDropsSection.getKeys(false);
                    double xpToDrop = 0;

                    //main drop sections, direct children of "block.drops"
                    drop:
                    for (String drop : drops) {
                        if (parentDropsSection.isConfigurationSection(drop)) {
                            ConfigurationSection dropSection = parentDropsSection.getConfigurationSection(drop);

                            if (dropSection.contains("conditions")) {
                                if (player != null) {
                                    for (String condition : dropSection.getStringList("conditions")) {
                                        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(condition));
                                        if (enchantment == null) {
                                            console.severe(ChatColor.RED + "The enchantment \"" + condition + "\" is not a valid enchantment");
                                            continue drop;
                                        } else if (!player.getInventory().getItemInMainHand().containsEnchantment(Enchantment.getByKey(NamespacedKey.minecraft(condition)))) {
                                            continue drop;
                                        }
                                    }
                                } else {
                                    continue;
                                }
                            }

                            if (dropSection.contains("chance")) {
                                double chance = dropSection.getDouble("chance");
                                if (chance > 0 && chance < 1) {
                                    if (Math.random() >= chance) {
                                        continue;
                                    }
                                } else {
                                    console.severe(ChatColor.RED + "The \"chance\" tag in the drop section \"" + drop + "\" for the custom block \"" + id + "\" must be greater than 0 and less than 1");
                                }
                            }

                            if (dropSection.contains("set-xp")) {
                                xpToDrop = dropSection.getDouble("set-xp");
                            }
                            if (dropSection.contains("add-xp")) {
                                xpToDrop += dropSection.getDouble("add-xp");
                            }
                            if (dropSection.contains("multiply-xp")) {
                                xpToDrop *= dropSection.getDouble("multiply-xp");
                            }

                            Set<String> keys = dropSection.getKeys(false);
                            //children of a main drop section, includes keys like "chance", "conditions", and items
                            for (String key : keys) {
                                Material material = Material.matchMaterial(key);
                                ItemStack item;
                                if (idToDefinitionFile.containsKey(key)) {
                                    //custom item
                                    item = getItemFromID(key);
                                } else if (material != null) {
                                    //vanilla material
                                    item = new ItemStack(material);
                                } else {
                                    if (!key.equalsIgnoreCase("chance") && !key.equalsIgnoreCase("conditions") && !key.equalsIgnoreCase("set-xp") && !key.equalsIgnoreCase("add-xp") && !key.equalsIgnoreCase("multiply-xp")) {
                                        if (debug >= 3) {
                                            console.warning(ChatColor.YELLOW + "The item \"" + key + "\" in the drop section \"" + drop + "\" of the custom block \"" + id + "\" is not a valid custom item id or vanilla item type");
                                        }
                                    }
                                    continue;
                                }

                                if (dropSection.isConfigurationSection(key)) {
                                    //configuration section format: material or custom item id as key, count and nbt as children
                                    ConfigurationSection itemSection = dropSection.getConfigurationSection(key);
                                    if (itemSection.contains("nbt")) {
                                        NBTItem nbtItem = new NBTItem(item);
                                        String nbtString = itemSection.getString("nbt");
                                        NBTCompound nbtMerge;

                                        if (nbtString.contains("id:") && nbtString.contains("Count:")) {
                                            //"nbt" is a full nbt item string
                                            nbtMerge = new NBTItem(NBTItem.convertNBTtoItem(new NBTContainer(nbtString)));
                                        } else {
                                            //"nbt" is just keys and not a full item
                                            nbtMerge = new NBTContainer(nbtString);
                                        }

                                        nbtItem.mergeCompound(nbtMerge);
                                        item = nbtItem.getItem();
                                    }

                                    if (itemSection.contains("count")) {
                                        Object value = itemSection.get("count");
                                        int count = 1;

                                        if (value instanceof Number) {
                                            count = ((Number) value).intValue();
                                        } else if (value instanceof String && ((String) value).contains("-") && ((String) value).length() >= 3) {
                                            String[] range = ((String) value).split("-");
                                            int min = Integer.parseInt(range[0]);
                                            int max = Integer.parseInt(range[1]);
                                            count = (int) (Math.random() * (max - min + 1) + min);
                                        }
                                        item.setAmount(count);
                                    }

                                } else {
                                    //vanilla item without NBT:   [material]: [count]
                                    Object value = dropSection.get(key);
                                    int count = 1;

                                    if (value instanceof Integer) {
                                        count = (Integer) value;
                                    } else if (value instanceof String && ((String) value).contains("-") && ((String) value).length() >= 3) {
                                        String[] range = ((String) value).split("-");
                                        int min = Integer.parseInt(range[0]);
                                        int max = Integer.parseInt(range[1]);
                                        count = (int) (Math.random() * (max - min + 1) + min);
                                    }

                                    item.setAmount(count);
                                }
                                newDrops.add(item);
                            }
                        }
                    }

                    if (originalDrops != null) {
                        originalDrops.clear();
                    }

                    int xpToDropInt = (int) xpToDrop;
                    if (xpToDropInt > 0) {
                        ((ExperienceOrb) loc.getWorld().spawnEntity(loc, EntityType.EXPERIENCE_ORB)).setExperience(xpToDropInt);
                    }
                    newDrops.forEach(item -> loc.getWorld().dropItemNaturally(loc, item));

                    String successMsg = "The custom block \"" + id + "\" successfully dropped ";
                    if (debug >= 3) {
                        if (newDrops.isEmpty()) {
                            successMsg += "no items";
                        } else {
                            successMsg += "the items " + newDrops.toString().replace("[", "").replace("]", "");
                        }

                        successMsg += " and " + xpToDropInt + " xp";

                        console.info(ChatColor.LIGHT_PURPLE + successMsg);
                    }
                } else {
                    if (debug >= 3) {
                        console.warning(ChatColor.YELLOW + "The drops for the custom block \"" + id + "\" were not changed because the \"enabled\" option in its drops section is set to false");
                    }
                }
            } else {
                if (debug >= 3) {
                    console.warning(ChatColor.YELLOW + "The drops for the custom block \"" + id + "\" could not be loaded because its drop section does not exist");
                }
            }
        } else {
            console.severe(ChatColor.RED + "The drops for the custom block \"" + id + "\" could not be loaded because its definition does not exist in the file " + path);
        }
    }

    //moves the logged location of a logged block (mainly for piston events)
    public void moveLoggedBlock(Location loc, Location newLoc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int subChunkY = y >> 4;

        File file = new File(main.getDataFolder() + File.separator + "BlockDatabase" + File.separator + world.getName(), "chunk." + chunkX + "." + chunkZ + ".yml");
        YamlConfiguration yaml = main.loadYamlFromFile(file, false, false, debug, "");

        String logPath = "subChunk" + subChunkY + "." + x + "_" + y + "_" + z;
        if (yaml != null && yaml.isConfigurationSection(logPath)) {
            Map<String, Object> values = yaml.getConfigurationSection(logPath).getValues(true);
            yaml.set(logPath, null);

            String newLogPath = "subChunk" + subChunkY + "." + newLoc.getBlockX() + "_" + newLoc.getBlockY() + "_" + newLoc.getBlockZ();
            yaml.createSection(newLogPath, values);
            try {
                yaml.save(file);
            } catch (IOException e) {
                console.severe(ChatColor.RED + "Could not save the file " + file.getPath() + " while trying to move the logged custom block at " + logPath);
            }
        }
    }

    //handles custom blocks that are being pushed/pulled by a piston, return of "true" means to cancel the event
    public boolean handleMovedBlocks(List<Block> blocks, BlockFace blockFace, boolean pushing) {
        Map<Location, Location> moves = new HashMap<>();
        if (!blocks.isEmpty()) {
            for (Block block : blocks) {
                String id = (String) getLoggedObjectFromLocation(block.getLocation(), "id");
                if (id != null) {
                    YamlConfiguration yaml = idToDefinitionFile.get(id);
                    Location loc = block.getLocation();
                    Location newLoc = block.getRelative(blockFace).getLocation();
                    String locString = loc.getWorld().getName() + ", " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
                    String newLocString = newLoc.getWorld().getName() + ", " + newLoc.getBlockX() + ", " + newLoc.getBlockY() + ", " + newLoc.getBlockZ();

                    if (yaml != null) {
                        if (!yaml.getBoolean(id + ".block.options.unbreakable", false)) {
                            //if the block is not unbreakable
                            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                                destroyLoggedBlock(null, false, loc, true, null, null, false);
                                if (debug >= 3) {
                                    console.info(ChatColor.LIGHT_PURPLE + "Destroyed the custom block \"" + id + "\" because the server-side block is breakable by pistons");
                                }
                            }
                            if (yaml.getBoolean(id + ".block.options.piston-breakable") && newLoc.getBlock().getType().isEmpty()) {
                                //if the block (normally not broken) can be broken by pistons
                                destroyLoggedBlock(null, false, loc, true, null, null, false);
                                if (debug >= 3) {
                                    console.info(ChatColor.LIGHT_PURPLE + "Broke the custom block \"" + id + "\" because it was pushed by a piston and \"block.options.piston-breakable\" is true");
                                }
                            }
                        } else {
                            if (debug >= 3) {
                                console.info(ChatColor.LIGHT_PURPLE + "The custom block \"" + id + "\" was not broken by a piston because it is unbreakable");
                            }
                        }

                        if (pushing) {
                            if (!yaml.getBoolean(id + ".block.options.cancel-piston-push")) {
                                moves.put(loc, newLoc);
                                if (debug >= 3) {
                                    console.info(ChatColor.LIGHT_PURPLE + "Pushed the custom block \"" + id + "\" from " + locString + " to " + newLocString);
                                }
                            } else {
                                return true;
                            }
                        } else {
                            if (!yaml.getBoolean(id + ".block.options.cancel-piston-pull")) {
                                moves.put(loc, newLoc);
                                if (debug >= 3) {
                                    console.info(ChatColor.LIGHT_PURPLE + "Pulled the custom block \"" + id + "\" from " + locString + " to " + newLocString);
                                }
                            } else {
                                return true;
                            }
                        }
                    }
                }
            }
            for (Map.Entry<Location, Location> entry : moves.entrySet()) {
                moveLoggedBlock(entry.getKey(), entry.getValue());
            }
        }
        return false;
    }

    //gets the ItemStack specified in the "item" tag of a block definition from an id, throws a NullPointerException if the tag doesn't exist
    public ItemStack getItemFromID(String id) {
        if (id != null && idToDefinitionFile.containsKey(id)) {
            YamlConfiguration yaml = idToDefinitionFile.get(id);
            String itemString = yaml.getString(id + ".item");

            if (itemString != null) {
                NBTItem nbtItem = new NBTItem(NBTItem.convertNBTtoItem(new NBTContainer(itemString)));
                NBTCompound nbtCompound = nbtItem.getOrCreateCompound("WoZItem");

                if (!nbtCompound.getBoolean("IsCustomItem")) {
                    nbtCompound.setBoolean("IsCustomItem", true);
                }

                if (nbtCompound.getString("CustomItem").equals("")) {
                    nbtCompound.setString("CustomItem", id);
                }

                return nbtItem.getItem();
            } else {
                throw new NullPointerException();
            }
        } else {
            console.severe(ChatColor.RED + "No item was loaded for the custom block \"" + id + "\" because the block ID is null or does not exist");
        }

        return null;
    }

    //uses the coords of a new and old chunk (usually from a movement event) to determine the direction a player moved between chunks
    /*public void chunkDirectionCheck(World world, Chunk newChunk, Chunk oldChunk, Player player) {
        int chunkCount = 0;
        int blockCount = 0;
        int chunkDiffX = newChunk.getX() - oldChunk.getX();
        int chunkDiffZ = newChunk.getZ() - oldChunk.getZ();

        if (chunkDiffX == 0 && chunkDiffZ < 0) {
            //facing north: -z
            for (int x = -4; x <= 4; x++) {
                Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() - 4);
                blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                chunkCount++;
            }
        } else if (chunkDiffX > 0 && chunkDiffZ == 0) {
            //facing east: +x
            for (int z = -4; z <= 4; z++) {
                Chunk chunk = world.getChunkAt(newChunk.getX() + 4, newChunk.getZ() + z);
                blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                chunkCount++;
            }
        } else if (chunkDiffX == 0 && chunkDiffZ > 0) {
            //facing south: +z
            for (int x = -4; x <= 4; x++) {
                Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() + 4);
                blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                chunkCount++;
            }
        } else if (chunkDiffX < 0 && chunkDiffZ == 0) {
            //facing west: -x
            for (int z = -4; z <= 4; z++) {
                Chunk chunk = world.getChunkAt(newChunk.getX() - 4, newChunk.getZ() + z);
                blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                chunkCount++;
            }
        } else if (chunkDiffX > 0 && chunkDiffZ < 0) {
            //facing northeast: +x, -z
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (x == 4 || z == -4) {
                        Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() + z);
                        blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                        chunkCount++;
                    }
                }
            }
        } else if (chunkDiffX > 0) {
            //facing southeast: +x, +z
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (x == 4 || z == 4) {
                        Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() + z);
                        blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                        chunkCount++;
                    }
                }
            }
        } else if (chunkDiffX < 0 && chunkDiffZ > 0) {
            //facing southwest: -x, +z
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (x == -4 || z == 4) {
                        Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() + z);
                        blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                        chunkCount++;
                    }
                }
            }
        } else {
            //facing northwest: -x, -z
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (x == -4 || z == -4) {
                        Chunk chunk = world.getChunkAt(newChunk.getX() + x, newChunk.getZ() + z);
                        blockCount += LoadCustomBlocksFromChunkFile(player, newChunk, chunk);
                        chunkCount++;
                    }
                }
            }
        }

        if (debug) {
            console.info(ChatColor.AQUA + String.valueOf(chunkCount) + " total chunks checked and " + blockCount + " blocks loaded by " + player.getName());
        }
    }*/
}
