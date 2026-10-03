package com.lightframe.monitor;
/** A single palette shared by screen cards and the history renderer. */
public final class ThemeColors {
 public final boolean dark;
 public final int background,card,ink,muted,accent,button,navigation,selected,line,grid,axis,curve,cursor;
 public ThemeColors(boolean dark){this.dark=dark;
  background=dark?0xFF17161D:0xFFF8F5FF;card=dark?0xFF292735:0xFFE2DFED;
  ink=dark?0xFFE9E6F2:0xFF3F3A49;muted=dark?0xFFB7B0C8:0xFF706B82;
  accent=dark?0xFFBEC3FF:0xFF656D9E;button=dark?0xFF3B3A50:0xFFDCDCF1;
  navigation=dark?0xFF211F2B:0xFFEEEBF6;selected=dark?0xFF484561:0xFFDBDCF4;
  line=dark?0xFF494454:0xFFD3D0E1;grid=dark?0xFF3D394A:0xFFD3CFDF;
  axis=dark?0xFF948CA9:0xFFAAA5BD;curve=dark?0xFFB5BEFF:0xFF65709D;cursor=dark?0xFFE2B9FF:0xFF8E8AC2;
 }
}
