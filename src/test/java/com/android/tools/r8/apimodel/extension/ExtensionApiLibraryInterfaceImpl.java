// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.apimodel.extension;

public class ExtensionApiLibraryInterfaceImpl implements ExtensionApiLibraryInterface {

  @Override
  public void extensionInterfaceApi() {
    System.out.println("ExtensionApiLibraryInterfaceImpl::extensionApi");
  }
}
