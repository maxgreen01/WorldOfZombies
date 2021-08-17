package me.woz.customplugins.commands.woz;

import de.tr7zw.nbtapi.NBTItem;
import de.tr7zw.nbtapi.NbtApiException;
import me.woz.customplugins.WorldOfZombies;
import me.woz.customplugins.commands.SubCommand;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

public class ItemCommand implements SubCommand {

    private final WorldOfZombies main;
    private final Logger console;

    public ItemCommand(WorldOfZombies main) {
        this.main = main;
        console = main.getLogger();
    }/*           /woz item | nbt -yaml             */

    @Override
    public boolean subCommand(CommandSender sender, Command cmd, String alias, String[] args) {
        if (sender.hasPermission("worldofzombies.command.item")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (args.length == 0) {
                    throw new IllegalArgumentException("Invalid command");
                }

                if (args[0].equalsIgnoreCase("nbt")) {
                    ItemStack item = player.getInventory().getItemInMainHand();
                    String itemString;
                    String msgString;
                    try {
                        itemString = NBTItem.convertItemtoNBT(item).toString();
                    } catch (NbtApiException e) {
                        sender.sendMessage(ChatColor.RED + "An error occurred while getting the NBT string of the " + ChatColor.YELLOW + item.getType() + ChatColor.RED + " in your main hand!");
                        return true;
                    }
                    if (args.length > 1 && args[1].equalsIgnoreCase("-yaml")) {
                        itemString = itemString.replaceAll("'", "''");
                        itemString = "'" + itemString + "'";
                        msgString = ChatColor.GREEN + "Click to copy the YAML formatted NBT string of the " + ChatColor.YELLOW + item.getType() + ChatColor.GREEN + " in your main hand!";
                    } else {
                        msgString = ChatColor.GREEN + "Click to copy the NBT string of the " + ChatColor.YELLOW + item.getType() + ChatColor.GREEN + " in your main hand!";
                    }

                    TextComponent message = new TextComponent(msgString);
                    message.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, itemString));
                    message.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to copy text!")));
                    player.spigot().sendMessage(message);
                } else {
                    throw new IllegalArgumentException("item,nbt");
                }
            } else {
                sender.sendMessage(ChatColor.RED + "This command can only be run by players!");
            }
        } else {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command!");
        }

        return true;
    }
}