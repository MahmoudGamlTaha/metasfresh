package de.metas.adempiere.form.terminal.field.constraint;

/*
 * #%L
 * de.metas.swat.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import de.metas.adempiere.form.terminal.ITerminalField;
import de.metas.adempiere.form.terminal.WrongValueException;

public interface ITerminalFieldConstraint<T>
{
	/**
	 * Evaluate if this field's value is correct considering the constraint's implementation.
	 * 
	 * @param field
	 * @param value
	 * 
	 * @throws WrongValueException
	 */
	void evaluate(ITerminalField<T> field, T value) throws WrongValueException;

}
