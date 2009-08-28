/*
 * Copyright (c) 2009, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.wabit.swingui.action;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;

import ca.sqlpower.wabit.report.Report;
import ca.sqlpower.wabit.report.Template;
import ca.sqlpower.wabit.swingui.WabitSwingSession;

/**
 * Creates a new report on a template.
 */
public class NewReportOnTemplateAction extends AbstractAction {
	private final WabitSwingSession session;
	private final Template template;

    public NewReportOnTemplateAction(WabitSwingSession session, Template template) {
        super("New Report on " + template.getName());
		this.session = session;
		this.template = template;
    }

    public void actionPerformed(ActionEvent e) {
		Report newReport = new Report(template, session);
		newReport.setName(template.getName() + " Report");
		session.getWorkspace().addReport(newReport);
    }
}
