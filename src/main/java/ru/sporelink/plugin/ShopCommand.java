package ru.sporelink.plugin;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

public class ShopCommand implements CommandExecutor, Listener {

    private final SporelinkPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String SHOP_TITLE = "§0Sporelink Shop";
    private static final String SYSTEM_UUID = "11111111111111111111111111111111";

    public ShopCommand(SporelinkPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin); // Регистрируем слушатель кликов
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        Inventory inv = Bukkit.createInventory(null, 27, SHOP_TITLE);

        // 1. Алмаз (10 монет)
        inv.setItem(10, createItem(Material.DIAMOND, "§bАлмаз", 10, "§7Крепкий и блестящий."));
        // 2. Золотое яблоко (25 монет)
        inv.setItem(12, createItem(Material.GOLDEN_APPLE, "§6Золотое яблоко", 25, "§7Дарует регенерацию."));
        // 3. Эндер-жемчуг (5 монет)
        inv.setItem(14, createItem(Material.ENDER_PEARL, "§5Эндер-жемчуг", 5, "§7Телепортация."));
        // 4. Зачарованное яблоко (100 монет)
        inv.setItem(16, createItem(Material.ENCHANTED_GOLDEN_APPLE, "§dЗачарованное яблоко", 100, "§7Божественное исцеление."));

        player.openInventory(inv);
        return true;
    }

    private ItemStack createItem(Material mat, String name, long price, String lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore, "", "§aЦена: §e" + price + " монет", "§7Нажмите для покупки"));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(SHOP_TITLE)) return;
        event.setCancelled(true); // Запрещаем вытаскивать предметы из GUI

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        long price = 0;
        if (clicked.getType() == Material.DIAMOND) price = 10;
        else if (clicked.getType() == Material.GOLDEN_APPLE) price = 25;
        else if (clicked.getType() == Material.ENDER_PEARL) price = 5;
        else if (clicked.getType() == Material.ENCHANTED_GOLDEN_APPLE) price = 100;
        else return;

        String fromUuid = player.getUniqueId().toString().replace("-", "");
        String nodeUrl = plugin.getNodeUrl();
        
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        player.sendMessage("§7Покупка...");

        final long finalPrice = price;
        final Material finalMat = clicked.getType();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Переводим деньги СИСТЕМЕ
                String body = String.format(
                        "{\"to_node\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d}",
                        nodeUrl, fromUuid, SYSTEM_UUID, finalPrice
                );

                HttpRequest req = HttpRequest.newBuilder(URI.create(nodeUrl + "/transfer"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res.statusCode() == 200 && res.body().contains("\"status\":\"committed\"")) {
                        // Выдаем предмет
                        player.getInventory().addItem(new ItemStack(finalMat, 1));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                        player.sendMessage("§aУспешно куплено за " + finalPrice + " монет!");
                    } else if (res.body().contains("insufficient balance")) {
                        player.sendMessage("§cНедостаточно монет!");
                    } else {
                        player.sendMessage("§cОшибка транзакции: " + res.body());
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    player.sendMessage("§cСетевая ошибка: " + e.getMessage())
                );
            }
        });
    }
}