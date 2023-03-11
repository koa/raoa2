/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package ch.bergturbenthal.raoa.viewer.interfaces.git.glue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Send a fixed status code to the client. */
public class ErrorServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final int status;

  /**
   * Sends a specific status code.
   *
   * @param status the HTTP status code to always send.
   */
  public ErrorServlet(int status) {
    this.status = status;
  }

  /** {@inheritDoc} */
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse rsp)
      throws ServletException, IOException {
    rsp.sendError(status);
  }
}
