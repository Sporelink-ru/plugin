package ru.sporelink.plugin;

import com.google.gson.*;
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
import java.util.*;

public class AuctionCommand implements CommandExecutor, Listener {

    private final SporelinkPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String AH_TITLE = "§0Аукцион";
    
    // Храним данные лота по слоту для каждого игрока
    private final Map<UUID, Map<Integer, AuctionLot>> openAuctions = new HashMap<>();

    record AuctionLot(int id, long price, String sellerUuid, String itemBase64) {}

    public AuctionCommand(SporelinkPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) {
            handleSell(player, args);
            return true;
        }

        openAuction(player);
        return true;
    }

    private void openAuction(Player player) {
        player.sendMessage("§7Загрузка аукциона...");
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(plugin.getHubUrl() + "/api/auction")).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonArray lots = JsonParser.parseString(res.body()).getAsJsonArray();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    Inventory inv = Bukkit.createInventory(null, 54, AH_TITLE);
                    Map<Integer, AuctionLot> lotMap = new HashMap<>();
                    
                    int slot = 0;
                    for (JsonElement el : lots) {
                        if (slot >= 45) break;
                        JsonObject lot = el.getAsJsonObject();
                        int id = lot.get("id").getAsInt();
                        String base64 = lot.get("itemBase64").getAsString();
                        long price = lot.get("price").getAsLong();
                        String sellerName = lot.get("sellerName").getAsString();
                        String sellerUuid = lot.get("sellerUuid").getAsString();
                        
                        try {
                            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
                            ItemMeta meta = item.getItemMeta();
                            List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                            lore.add("");
                            lore.add("§aЦена: §e" + price);
                            lore.add("§7Продавец: §f" + sellerName);
                            lore.add("§eНажмите, чтобы купить!");
                            meta.setLore(lore);
                            item.setItemMeta(meta);
                            
                            inv.setItem(slot, item);
                            lotMap.put(slot, new AuctionLot(id, price, sellerUuid, base64));
                            slot++;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Ошибка десериализации лота " + id);
                        }
                    }
                    
                    openAuctions.put(player.getUniqueId(), lotMap);
                    player.openInventory(inv);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cОшибка сети: " + e.getMessage()));
            }
        });
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cИспользование: /ah sell <цена> (держите предмет в руке)");
            return;
        }
        long price;
        try { price = Long.parseLong(args[1]); } catch (Exception e) {
            player.sendMessage("§cНеверная цена."); return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            player.sendMessage("§cВозьмите предмет в руку, чтобы продать."); return;
        }

        try {
            String base64 = Base64.getEncoder().encodeToString(item.serializeAsBytes());
            String sellerUuid = player.getUniqueId().toString().replace("-", "");
            
            String jsonBody = String.format("{\"sellerUuid\":\"%s\",\"itemBase64\":\"%s\",\"price\":%d}", sellerUuid, base64, price);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(plugin.getHubUrl() + "/api/auction"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
                    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (res.statusCode() == 200) {
                            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                            player.sendMessage("§aПредмет успешно выставлен на аукцион за " + price + " монет!");
                        } else {
                            player.sendMessage("§cОшибка выставления: " + res.body());
                        }
                    });
                } catch (Exception e) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cСетевая ошибка: " + e.getMessage()));
                }
            });
        } catch (Exception e) {
            player.sendMessage("§cОшибка сериализации предмета.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(AH_TITLE)) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        
        Map<Integer, AuctionLot> lotMap = openAuctions.get(player.getUniqueId());
        if (lotMap == null || !lotMap.containsKey(slot)) return;

        AuctionLot lot = lotMap.get(slot);
        player.closeInventory();
        player.sendMessage("§7Обработка покупки...");

        String buyerUuid = player.getUniqueId().toString().replace("-", "");
        String nodeUrl = plugin.getNodeUrl();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // ШАГ 1: Переводим деньги от покупателя продавцу
                String transferBody = String.format(
                        "{\"to_node\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d}",
                        nodeUrl, buyerUuid, lot.sellerUuid(), lot.price()
                );
                
                HttpRequest transferReq = HttpRequest.newBuilder(URI.create(nodeUrl + "/transfer"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(transferBody)).build();
                
                HttpResponse<String> transferRes = http.send(transferReq, HttpResponse.BodyHandlers.ofString());

                if (transferRes.statusCode() != 200 || !transferRes.body().contains("\"status\":\"committed\"")) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (transferRes.body().contains("insufficient balance")) {
                            player.sendMessage("§cНедостаточно монет для покупки!");
                        } else {
                            player.sendMessage("§cОшибка транзакции: " + transferRes.body());
                        }
                    });
                    return;
                }

                // ШАГ 2: Если деньги ушли, покупаем предмет в Хабе
                String buyBody = String.format("{\"buyerUuid\":\"%s\"}", buyerUuid);
                HttpRequest buyReq = HttpRequest.newBuilder(URI.create(plugin.getHubUrl() + "/api/auction/" + lot.id() + "/buy"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(buyBody)).build();
                HttpResponse<String> buyRes = http.send(buyReq, HttpResponse.BodyHandlers.ofString());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (buyRes.statusCode() == 200) {
                        JsonObject json = JsonParser.parseString(buyRes.body()).getAsJsonObject();
                        String itemBase64 = json.get("itemBase64").getAsString();
                        try {
                            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(itemBase64));
                            player.getInventory().addItem(item);
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                            player.sendMessage("§aПредмет успешно куплен!");
                        } catch (Exception e) {
                            player.sendMessage("§cОшибка выдачи предмета.");
                        }
                    } else {
                        player.sendMessage("§cОшибка покупки в Хабе: " + buyRes.body());
                    }
                });
                
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cСетевая ошибка: " + e.getMessage()));
            }
        });
    }
}