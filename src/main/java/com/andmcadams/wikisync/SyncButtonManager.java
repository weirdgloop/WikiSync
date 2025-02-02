package com.andmcadams.wikisync;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Component;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
public class SyncButtonManager {

    private static final int COLLECTION_LOG_SETUP = 7797;
    private static final int[] SPRITE_IDS_INACTIVE = {
            SpriteID.DIALOG_BACKGROUND,
            SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_TOP_LEFT,
            SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_TOP_RIGHT,
            SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_BOTTOM_LEFT,
            SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_BOTTOM_RIGHT,
            SpriteID.WORLD_MAP_BUTTON_EDGE_LEFT,
            SpriteID.WORLD_MAP_BUTTON_EDGE_TOP,
            SpriteID.WORLD_MAP_BUTTON_EDGE_RIGHT,
            SpriteID.WORLD_MAP_BUTTON_EDGE_BOTTOM,
    };

    private static final int[] SPRITE_IDS_ACTIVE = {
            SpriteID.RESIZEABLE_MODE_SIDE_PANEL_BACKGROUND,
            SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_TOP_LEFT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_TOP_RIGHT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_BOTTOM_LEFT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_BOTTOM_RIGHT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_EDGE_LEFT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_EDGE_TOP_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_EDGE_RIGHT_HOVERED,
            SpriteID.EQUIPMENT_BUTTON_EDGE_BOTTOM_HOVERED,
    };

    private static final int FONT_COLOUR_INACTIVE = 0xd6d6d6;
    private static final int FONT_COLOUR_ACTIVE = 0xffffff;

    private final Client client;
    private final ClientThread clientThread;
    private final EventBus eventBus;

    @Inject
    private SyncButtonManager(
            Client client,
            ClientThread clientThread,
            EventBus eventBus
    )
    {
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
    }

    public void startUp()
    {
        eventBus.register(this);
        clientThread.invokeLater(() -> tryAddButton(this::onButtonClick));
    }

    public void shutDown()
    {
        eventBus.unregister(this);
        clientThread.invokeLater(this::removeButton);
    }

//    @Subscribe
//    public void onMenuOptionClicked(MenuOptionClicked m) {
//        log.error("opt {}", m.getMenuOption());
//        log.error("action {}", m.getMenuAction().toString());
//        log.error("p0 {}", m.getParam0());
//        log.error("p1 {}", m.getParam1());
//        log.error("id {}", m.getId());
//        log.error("itemId {}", m.getItemId());
//        log.error("target {}", m.getMenuTarget());
//    }

    @Getter
    @RequiredArgsConstructor
    enum Screen
    {
        // First number is col log container (inner) and second is search button id
        COLLECTION_LOG(40697944, 40697932, ComponentID.COLLECTION_LOG_CONTAINER, 55),
        ;

        /**
         * parent widget of the interface, install target
         */
        @Getter(onMethod_ = @Component)
        private final int parentId;

        /**
         * the "Set Bonus" button widget layer
         */
        @Getter(onMethod_ = @Component)
        private final int searchButtonId;

        @Getter(onMethod_ = @Component)
        private final int collectionLogContainer;

        /**
         * OriginalX for Set Bonus and Stat Bonus, prior to us moving them around (for shutdown)
         **/
        private final int originalX;

    }

    void tryAddButton(Runnable onClick)
    {
        for (Screen screen : Screen.values())
        {
            addButton(screen, onClick);
        }
    }
    @Subscribe
    public void onScriptPostFired(ScriptPostFired scriptPostFired)
    {
        if (scriptPostFired.getScriptId() == COLLECTION_LOG_SETUP)
        {
            removeButton();
            addButton(Screen.COLLECTION_LOG, this::onButtonClick);
        }
    }

    void onButtonClick() {
        client.menuAction(-1, 40697932, MenuAction.CC_OP, 1, -1, "Search", null);
        client.addChatMessage(ChatMessageType.CONSOLE, "WikiSync", "Your collection log data is being sent to WikiSync...", "WikiSync");
    }

    /**
     * Shifts over the Set Bonus / Stat Bonus buttons
     * and adds new widgets to make a visually equal button with a different name.
     */
    void addButton(Screen screen, Runnable onClick)
    {
        Widget parent = client.getWidget(screen.getParentId());
        Widget setBonus = client.getWidget(screen.getSearchButtonId());
        Widget collectionLogContainer = client.getWidget(screen.getCollectionLogContainer());
        Widget[] containerChildren;
        Widget draggableTopbar;
        Widget[] refComponents;
        if (parent == null || setBonus == null || collectionLogContainer == null ||
            (containerChildren = collectionLogContainer.getChildren()) == null ||
                (draggableTopbar = containerChildren[0]) == null  ||
                (refComponents = setBonus.getChildren()) == null)
        {
            return;
        }

        // Since the Set Bonus button uses absolute positioning,
        // we must also use absolute for all the children below,
        // which means it's necessary to offset the values by simulating corresponding pos/size modes.
        final int w = setBonus.getOriginalWidth();
        final int h = setBonus.getOriginalHeight();
        final int x = parent.getOriginalWidth() - 103;
        final int y = setBonus.getOriginalY();

        final Widget[] spriteWidgets = new Widget[9];

        // the background uses ABSOLUTE_CENTER and MINUS sizing
        int bgWidth = w - refComponents[0].getOriginalWidth();
        int bgHeight = h - refComponents[0].getOriginalHeight();
        int bgX = (x) + (w - bgWidth) / 2;
        int bgY = (y) + (h - bgHeight) / 2;
        spriteWidgets[0] = parent.createChild(-1, WidgetType.GRAPHIC)
                .setSpriteId(refComponents[0].getSpriteId())
                .setPos(bgX, bgY)
                .setSize(bgWidth, bgHeight)
                .setYPositionMode(setBonus.getYPositionMode());
        spriteWidgets[0].revalidate();

        // borders and corners
        for (int i = 1; i < 9; i++)
        {
            Widget c = spriteWidgets[i] = parent.createChild(-1, WidgetType.GRAPHIC)
                    .setSpriteId(refComponents[i].getSpriteId())
                    .setSize(refComponents[i].getOriginalWidth(), refComponents[i].getOriginalHeight())
                    .setPos(x + refComponents[i].getOriginalX(), y + refComponents[i].getOriginalY());
            spriteWidgets[i].revalidate();
        }

        final Widget text = parent.createChild(-1, WidgetType.TEXT)
                .setText("WikiSync")
                .setTextColor(FONT_COLOUR_INACTIVE)
                .setFontId(refComponents[10].getFontId())
                .setTextShadowed(refComponents[10].getTextShadowed())
                .setXTextAlignment(WidgetTextAlignment.CENTER)
                .setYTextAlignment(WidgetTextAlignment.CENTER)
                .setPos(x, y)
                .setSize(w, h)
                .setYPositionMode(setBonus.getYPositionMode());
        text.revalidate();

        // We'll give the text layer the listeners since it covers the whole area
        text.setHasListener(true);
        text.setOnMouseOverListener((JavaScriptCallback) ev ->
        {
            for (int i = 0; i <= 8; i++)
            {
                spriteWidgets[i].setSpriteId(SPRITE_IDS_ACTIVE[i]);
            }
            text.setTextColor(FONT_COLOUR_ACTIVE);
        });
        text.setOnMouseLeaveListener((JavaScriptCallback) ev ->
        {
            for (int i = 0; i <= 8; i++)
            {
                spriteWidgets[i].setSpriteId(SPRITE_IDS_INACTIVE[i]);
            }
            text.setTextColor(FONT_COLOUR_INACTIVE);
        });

        // register a click listener
        text.setAction(0, "Sync your collection log with WikiSync");
        text.setOnOpListener((JavaScriptCallback) ev -> onClick.run());


        //Shrink the top bar to avoid overlapping the new button
        draggableTopbar.setOriginalWidth(draggableTopbar.getWidth() - w);
        draggableTopbar.revalidate();

        // recompute locations / sizes on parent
        parent.revalidate();
    }

    void removeButton()
    {
        for (Screen screen : Screen.values())
        {
            Widget parent = client.getWidget(screen.getParentId());
            if (parent != null)
            {
                parent.deleteAllChildren();
                parent.revalidate();
            }
        }
    }
}
