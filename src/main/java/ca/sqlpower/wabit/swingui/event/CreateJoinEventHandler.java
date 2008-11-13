/*
 * Copyright (c) 2008, SQL Power Group Inc.
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

package ca.sqlpower.wabit.swingui.event;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import ca.sqlpower.swingui.CursorManager;
import ca.sqlpower.wabit.query.SQLJoin;
import ca.sqlpower.wabit.swingui.querypen.ConstantPNode;
import ca.sqlpower.wabit.swingui.querypen.JoinLine;
import ca.sqlpower.wabit.swingui.querypen.QueryPen;
import ca.sqlpower.wabit.swingui.querypen.UnmodifiableItemPNode;
import ca.sqlpower.wabit.swingui.querypen.MouseState.MouseStates;
import edu.umd.cs.piccolo.PCanvas;
import edu.umd.cs.piccolo.PLayer;
import edu.umd.cs.piccolo.PNode;
import edu.umd.cs.piccolo.event.PBasicInputEventHandler;
import edu.umd.cs.piccolo.event.PInputEvent;

/**
 * Creates a join between two columns in two different tables.
 */
public class CreateJoinEventHandler extends PBasicInputEventHandler {
	
	private QueryPen mouseStatePane;
	private UnmodifiableItemPNode leftText;
	private UnmodifiableItemPNode rightText;
	private PLayer joinLayer;
	private PCanvas canvas;
	private CursorManager cursorManager;
	private double mouseFirstClickX;
	private double mouseSecondClickX;

	
	private PropertyChangeListener changeListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent evt) {
			for (PropertyChangeListener l : createJoinListeners) {
				l.propertyChange(evt);
			}
		}
	};
	
	private List<PropertyChangeListener> createJoinListeners = new ArrayList<PropertyChangeListener>();

	public CreateJoinEventHandler(QueryPen mouseStatePane, PLayer joinLayer, PCanvas canvas, CursorManager cursorManager) {
		this.mouseStatePane = mouseStatePane;
		this.joinLayer = joinLayer;
		this.canvas = canvas;
		this.cursorManager = cursorManager;
		this.mouseFirstClickX=0;
		this.mouseSecondClickX=0;
	}
	
	@Override
	public void mousePressed(PInputEvent event) {
		super.mousePressed(event);
		if (mouseStatePane.getMouseState().equals(MouseStates.CREATE_JOIN)) {
			PNode pick = event.getPickedNode();
			while (pick != null && !(pick instanceof UnmodifiableItemPNode)) {
				
				if(pick instanceof ConstantPNode) {
					JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(canvas), "Joining on constants is not allowed.");
				}
				pick = pick.getParent();
			}
			if (pick != null) {
				if (leftText == null) {
					mouseFirstClickX = event.getPosition().getX();
					leftText = (UnmodifiableItemPNode)pick;
					leftText.setJoiningState(true);
				} else if (rightText == null) {
					leftText.setJoiningState(false);
					mouseSecondClickX = event.getPosition().getX();
					rightText = (UnmodifiableItemPNode)pick;
					if(leftText.getParent() == rightText.getParent()) {
						JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(canvas), "You cannot join to your own Table.");
						leftText = null;
						rightText = null;
						cursorManager.placeModeFinished();
						mouseStatePane.setMouseState(MouseStates.READY);
						return;
					}
					if ( mouseFirstClickX != 0 && mouseSecondClickX!= 0 && mouseFirstClickX > mouseSecondClickX) {
						UnmodifiableItemPNode tempNode = leftText;
						leftText = rightText;
						rightText = tempNode;
						mouseFirstClickX = mouseSecondClickX = 0;
					}
					JoinLine join = new JoinLine(mouseStatePane, canvas, leftText, rightText);
					join.getModel().addJoinChangeListener(changeListener);
					joinLayer.addChild(join);
					for(PropertyChangeListener listener : createJoinListeners) {
						listener.propertyChange(new PropertyChangeEvent(canvas, SQLJoin.PROPERTY_JOIN_ADDED, null, join.getModel()));
					}
					leftText = null;
					rightText = null;
					cursorManager.placeModeFinished();
					mouseStatePane.setMouseState(MouseStates.READY);
				} else {
					throw new IllegalStateException("Trying to create a join while both ends have already been specified.");
				}
			} else {
				if(leftText != null) {
					leftText.setJoiningState(false);
				}
				leftText = null;
				rightText = null;
				cursorManager.placeModeFinished();
				mouseStatePane.setMouseState(MouseStates.READY);
			}
		}
	}
	
	public void addCreateJoinListener(PropertyChangeListener l) {
		createJoinListeners.add(l);
	}
	
	public void removeCreateJoinListener(PropertyChangeListener l) {
		createJoinListeners.remove(l);
	}
}
