// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

/**
 * A section as defined by the Dex file format.
 */
public class DexSection {
  public final int type;
  public final int length;
  public final int offset;
  private int end;

  public DexSection(int type, int unused, int length, int offset) {
    this.type = type;
    assert unused == 0;
    this.length = length;
    this.offset = offset;
    this.end = -1;
  }

  public static int[] getConstants() {
    return new int[] {
      Constants.TYPE_ENCODED_ARRAY_ITEM,
      Constants.TYPE_HEADER_ITEM,
      Constants.TYPE_DEBUG_INFO_ITEM,
      Constants.TYPE_FIELD_ID_ITEM,
      Constants.TYPE_ANNOTATION_SET_REF_LIST,
      Constants.TYPE_STRING_ID_ITEM,
      Constants.TYPE_MAP_LIST,
      Constants.TYPE_PROTO_ID_ITEM,
      Constants.TYPE_METHOD_ID_ITEM,
      Constants.TYPE_TYPE_ID_ITEM,
      Constants.TYPE_STRING_DATA_ITEM,
      Constants.TYPE_CLASS_DATA_ITEM,
      Constants.TYPE_TYPE_LIST,
      Constants.TYPE_ANNOTATIONS_DIRECTORY_ITEM,
      Constants.TYPE_ANNOTATION_ITEM,
      Constants.TYPE_ANNOTATION_SET_ITEM,
      Constants.TYPE_CLASS_DEF_ITEM,
      Constants.TYPE_CODE_ITEM
    };
  }

  void setEnd(int end) {
    this.end = end;
  }

  // Returns the byte size of this section.
  public int size() {
    return end - offset;
  }

  public String typeName() {
    return typeName(this.type);
  }

  public static String typeName(int type) {
    // Type names are in UpperCamelCase because they're used as labels in
    // benchmarks.
    switch (type) {
      case Constants.TYPE_HEADER_ITEM:
        return "Header";
      case Constants.TYPE_STRING_ID_ITEM:
        return "Strings";
      case Constants.TYPE_TYPE_ID_ITEM:
        return "Types";
      case Constants.TYPE_PROTO_ID_ITEM:
        return "Protos";
      case Constants.TYPE_FIELD_ID_ITEM:
        return "Fields";
      case Constants.TYPE_METHOD_ID_ITEM:
        return "Methods";
      case Constants.TYPE_CLASS_DEF_ITEM:
        return "ClassDefs";
      case Constants.TYPE_MAP_LIST:
        return "Maps";
      case Constants.TYPE_TYPE_LIST:
        return "TypeLists";
      case Constants.TYPE_ANNOTATION_SET_REF_LIST:
        return "AnnotationSetRefs";
      case Constants.TYPE_ANNOTATION_SET_ITEM:
        return "AnnotationSets";
      case Constants.TYPE_CLASS_DATA_ITEM:
        return "ClassData";
      case Constants.TYPE_CODE_ITEM:
        return "Code";
      case Constants.TYPE_STRING_DATA_ITEM:
        return "StringData";
      case Constants.TYPE_DEBUG_INFO_ITEM:
        return "DebugInfo";
      case Constants.TYPE_ANNOTATION_ITEM:
        return "Annotation";
      case Constants.TYPE_ENCODED_ARRAY_ITEM:
        return "EncodedArrays";
      case Constants.TYPE_ANNOTATIONS_DIRECTORY_ITEM:
        return "AnnotationsDirectory";
      default:
        return "Unknown";
    }
  }

  @Override
  public String toString() {
    return typeName() + " @" + offset + " " + length;
  }
}
