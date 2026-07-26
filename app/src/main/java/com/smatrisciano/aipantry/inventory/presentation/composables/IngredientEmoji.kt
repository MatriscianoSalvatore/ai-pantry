package com.smatrisciano.aipantry.inventory.presentation.composables

/**
 * Emoji per un ingrediente: match per parole chiave sul nome, dalla più
 * specifica alla più generica — con un vocabolario di ~870 voci una mappa
 * esatta nome→emoji non è mantenibile. Fallback neutro 🍽️ solo per ciò che
 * non rientra in nessuna famiglia.
 */
fun ingredientEmoji(name: String): String {
    val n = name.lowercase()

    // Match su confini di parola: "ham" NON deve matchare "cHAMpagne"
    fun has(vararg keys: String) = keys.any { key ->
        Regex("\\b${Regex.escape(key)}").containsMatchIn(n)
    }

    return when {
        // Specifici prima delle famiglie: "strawberry jam" è 🫙, non 🍓,
        // e "salad dressing" è una salsa, non un'insalata
        has("jam", "marmalade", "chutney", "curd", "spread", "nutella") -> "🫙"
        has("dressing", "vinaigrette") -> "🥫"
        has("hamburger bun", "hot dog bun") -> "🍞"
        has("juice", "smoothie", "lemonade") -> "🧃"
        has("ice cream", "gelato", "sorbet", "popsicle") -> "🍦"
        has("frozen") -> "🧊"
        has("canned", " can", "spam") -> "🥫"

        // Latticini e uova
        has("milk", "kefir", "buttermilk", "cream", "custard", "pudding") -> "🥛"
        has("yogurt", "skyr", "quark") -> "🥣"
        has("cheese", "mozzarella", "burrata", "brie", "camembert", "gouda", "edam", "emmental",
            "gruyere", "feta", "halloumi", "gorgonzola", "provolone", "pecorino", "parmesan",
            "parmigiano", "grana", "asiago", "fontina", "taleggio", "stracchino", "robola",
            "robiola", "crescenza", "scamorza", "manchego", "paneer", "ricotta", "mascarpone",
            "caciocavallo", "cotija", "queso") -> "🧀"
        has("butter", "margarine", "ghee") -> "🧈"
        has("egg") -> "🥚"

        // Frutta
        has("watermelon") -> "🍉"
        has("melon", "cantaloupe") -> "🍈"
        has("strawberr") -> "🍓"
        has("blueberr", "currant", "goji", "acai", "mulberr", "gooseberr", "cranberr") -> "🫐"
        has("raspberr", "blackberr") -> "🍇"
        has("grape") -> "🍇"
        has("banana", "plantain") -> "🍌"
        has("apple") -> "🍎"
        has("pear") -> "🍐"
        has("peach", "nectarine", "apricot") -> "🍑"
        has("cherry", "cherries") -> "🍒"
        has("orange", "clementine", "tangerine", "mandarin", "kumquat", "pomelo", "grapefruit") -> "🍊"
        has("lemon", "yuzu", "citron") -> "🍋"
        has("lime") -> "🍋"
        has("pineapple") -> "🍍"
        has("mango") -> "🥭"
        has("kiwi") -> "🥝"
        has("coconut") -> "🥥"
        has("avocado", "guacamole") -> "🥑"
        has("fig", "date", "prune", "raisin", "sultana") -> "🍇"
        has("pomegranate", "persimmon", "quince", "lychee", "passion", "guava", "starfruit",
            "dragon fruit", "papaya", "jackfruit", "physalis") -> "🥭"

        // Verdure
        has("tomato", "passata") -> "🍅"
        has("eggplant") -> "🍆"
        has("potato") -> "🥔"
        has("carrot") -> "🥕"
        has("corn") -> "🌽"
        has("pepper", "chili", "jalapeno", "habanero", "poblano", "chipotle", "pepperoncini") -> "🌶️"
        has("cucumber", "zucchini", "pickle", "gherkin", "cornichon") -> "🥒"
        has("lettuce", "salad", "spinach", "arugula", "kale", "chard", "greens", "watercress",
            "endive", "radicchio", "frisee", "sorrel", "microgreens", "sprouts", "cress",
            "coleslaw", "bok choy", "cabbage") -> "🥬"
        has("broccoli", "broccolini", "romanesco", "asparagus", "artichoke", "brussels",
            "celery", "leek", "fennel", "okra") -> "🥦"
        has("onion", "shallot", "scallion") -> "🧅"
        has("garlic") -> "🧄"
        has("mushroom", "porcini", "shiitake", "portobello", "enoki", "truffle") -> "🍄"
        has("pumpkin", "squash") -> "🎃"
        has("ginger", "turnip", "parsnip", "radish", "beet", "celeriac", "kohlrabi", "daikon",
            "horseradish") -> "🥕"
        has("pea", "edamame", "bean", "lentil", "chickpea", "soy chunk", "tofu", "tempeh",
            "seitan", "falafel", "hummus") -> "🫘"
        has("olive", "tapenade", "caper") -> "🫒"

        // Carne e pesce
        has("bacon", "pancetta", "guanciale", "lardo", "speck", "pork", "porchetta") -> "🥓"
        has("ham", "prosciutto", "mortadella", "salami", "chorizo", "pepperoni", "bresaola",
            "coppa", "culatello", "pastrami", "deli", "liverwurst", "pate", "nduja",
            "cotechino", "blood sausage") -> "🍖"
        has("sausage", "hot dog", "wurst") -> "🌭"
        has("chicken", "turkey", "duck", "quail", "poultry") -> "🍗"
        has("beef", "steak", "veal", "brisket", "oxtail", "venison", "lamb", "goat", "rabbit",
            "meatball", "burger", "kebab", "mince", "jerky", "biltong", "ribs") -> "🥩"
        has("salmon", "tuna", "cod", "halibut", "trout", "mackerel", "herring", "sardine",
            "anchov", "swordfish", "sea bass", "sea bream", "tilapia", "catfish", "monkfish",
            "snapper", "eel", "fish", "surimi", "roe", "caviar") -> "🐟"
        has("shrimp", "prawn") -> "🦐"
        has("crab") -> "🦀"
        has("lobster", "crayfish") -> "🦞"
        has("squid", "octopus", "calamari") -> "🦑"
        has("mussel", "clam", "oyster", "scallop", "seafood") -> "🦪"

        // Carboidrati
        has("spaghetti", "linguine", "tagliatelle", "bucatini", "angel hair", "noodle",
            "ramen", "vermicelli") -> "🍜"
        has("pasta", "penne", "fusilli", "rigatoni", "farfalle", "macaroni", "orzo", "lasagna",
            "cannelloni", "orecchiette", "trofie", "paccheri", "ditalini", "gnocchi",
            "tortellini", "cappelletti", "agnolotti", "ravioli") -> "🍝"
        has("rice", "risotto", "paella") -> "🍚"
        has("bread", "baguette", "ciabatta", "focaccia", "sourdough", "brioche", "bun", "bagel",
            "muffin", "crumpet", "scone", "toast", "pita", "naan", "piadina", "carasau",
            "tortilla", "wrap", "taco") -> "🍞"
        has("croissant", "danish", "strudel", "pastry", "pie crust") -> "🥐"
        has("pizza") -> "🍕"
        has("pretzel", "breadstick", "taralli", "cracker", "crisp", "chips", "nachos") -> "🥨"
        has("flour", "semolina", "cornmeal", "polenta", "couscous", "quinoa", "barley",
            "buckwheat", "bulgur", "millet", "farro", "freekeh", "oat", "muesli", "granola",
            "cereal", "bran", "cornflake") -> "🌾"

        // Dolci e snack
        has("chocolate", "cocoa", "brownie") -> "🍫"
        has("cookie", "biscotti", "shortbread", "wafer", "ladyfinger") -> "🍪"
        has("cake", "panettone", "pandoro", "tiramisu", "cheesecake", "cannoli", "eclair",
            "macaron", "meringue", "profiterole", "cinnamon roll", "donut", "churro",
            "sfogliatelle", "tart", "pie") -> "🍰"
        has("candy", "marshmallow", "gummy", "licorice") -> "🍬"
        has("honey", "syrup", "molasses", "agave") -> "🍯"
        has("sugar", "sprinkles", "marzipan", "fondant", "vanilla") -> "🧁"
        has("popcorn") -> "🍿"
        has("almond", "walnut", "peanut", "cashew", "pistachio", "hazelnut", "pecan",
            "macadamia", "pine nut", "nut", "trail mix", "seeds") -> "🥜"

        // Bevande
        has("coffee", "espresso") -> "☕"
        has("tea", "matcha", "chamomile", "rooibos", "kombucha") -> "🍵"
        has("beer", "cider") -> "🍺"
        has("champagne", "prosecco") -> "🍾"
        has("wine", "vermouth", "aperol", "campari", "sake", "limoncello", "grappa") -> "🍷"
        has("water", "soda", "cola", "tonic", "ginger ale", "ginger beer", "root beer",
            "energy drink", "protein shake") -> "🥤"

        // Condimenti e dispensa
        has("oil") -> "🫗"
        has("vinegar", "dressing", "vinaigrette", "mayo", "aioli", "ketchup", "mustard",
            "sauce", "pesto", "salsa", "tahini", "miso", "gochujang", "harissa", "paste",
            "relish", "sriracha", "tzatziki", "chimichurri", "ponzu", "mirin", "dashi") -> "🥫"
        has("salt", "pepper", "paprika", "cumin", "turmeric", "curry", "cinnamon", "nutmeg",
            "clove", "cardamom", "saffron", "spice", "seasoning", "zaatar", "masala",
            "anise", "peppercorn", "flake") -> "🧂"
        has("basil", "parsley", "rosemary", "mint", "cilantro", "thyme", "oregano", "sage",
            "dill", "chives", "tarragon", "bay lea", "lemongrass", "herb") -> "🌿"
        has("broth", "stock", "bouillon", "soup", "minestrone") -> "🍲"
        has("yeast", "baking", "cornstarch", "gelatin", "protein powder") -> "🥄"
        has("seaweed", "nori", "wakame", "kombu", "furikake", "wasabi") -> "🍙"
        has("kimchi", "sauerkraut", "dumpling", "gyoza", "pierogi", "spring roll", "samosa",
            "wonton", "dolma", "arancini") -> "🥟"

        else -> "🍽️"
    }
}
