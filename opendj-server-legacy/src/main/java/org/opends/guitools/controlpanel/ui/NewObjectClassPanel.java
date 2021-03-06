/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2008-2010 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.guitools.controlpanel.ui;

import static org.opends.messages.AdminToolMessages.*;
import static org.opends.server.util.CollectionUtils.*;
import static org.opends.server.util.SchemaUtils.*;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizableMessageBuilder;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.opendj.ldap.schema.CoreSchema;
import org.forgerock.opendj.ldap.schema.ObjectClass;
import org.forgerock.opendj.ldap.schema.ObjectClassType;
import org.forgerock.opendj.ldap.schema.SchemaBuilder;
import org.opends.guitools.controlpanel.datamodel.ServerDescriptor;
import org.opends.guitools.controlpanel.event.ConfigurationChangeEvent;
import org.opends.guitools.controlpanel.event.ConfigurationElementCreatedListener;
import org.opends.guitools.controlpanel.event.SuperiorObjectClassesChangedEvent;
import org.opends.guitools.controlpanel.event.SuperiorObjectClassesChangedListener;
import org.opends.guitools.controlpanel.task.NewSchemaElementsTask;
import org.opends.guitools.controlpanel.task.Task;
import org.opends.guitools.controlpanel.ui.components.BasicExpander;
import org.opends.guitools.controlpanel.ui.components.DoubleAddRemovePanel;
import org.opends.guitools.controlpanel.ui.components.SuperiorObjectClassesEditor;
import org.opends.guitools.controlpanel.ui.renderer.SchemaElementComboBoxCellRenderer;
import org.opends.guitools.controlpanel.util.Utilities;
import org.opends.server.config.ConfigConstants;
import org.forgerock.opendj.ldap.schema.Schema;
import org.opends.server.util.ServerConstants;
import org.opends.server.util.StaticUtils;

/** The panel displayed when the user wants to define a new object class in the schema. */
public class NewObjectClassPanel extends StatusGenericPanel
{
 private static final long serialVersionUID = -4956885827963184571L;
  private JLabel lName = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_NAME_LABEL.get());
  private JLabel lSuperior = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_PARENT_LABEL.get());
  private JLabel lOID = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_OID_LABEL.get());
  private JLabel lAliases = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_ALIASES_LABEL.get());
  private JLabel lOrigin = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_ORIGIN_LABEL.get());
  private JLabel lFile = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_FILE_LABEL.get());
  private JTextField aliases = Utilities.createLongTextField();
  private JLabel lDescription = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_DESCRIPTION_LABEL.get());
  private JLabel lType = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_TYPE_LABEL.get());
  private JLabel lAttributes = Utilities.createPrimaryLabel(
      INFO_CTRL_PANEL_OBJECTCLASS_ATTRIBUTES_LABEL.get());

  private Set<AttributeType> inheritedOptionalAttributes = new HashSet<>();
  private Set<AttributeType> inheritedRequiredAttributes = new HashSet<>();

  private JLabel[] labels = {lName, lSuperior, lOID, lAliases, lOrigin, lFile,
      lDescription, lType, lAttributes
  };

  private JTextField name = Utilities.createMediumTextField();
  private SuperiorObjectClassesEditor superiors = new
  SuperiorObjectClassesEditor();
  private JComboBox<ObjectClassType> type = Utilities.createComboBox();
  private JTextField oid = Utilities.createMediumTextField();
  private JTextField description = Utilities.createLongTextField();
  private JTextField origin = Utilities.createLongTextField();
  private JTextField file = Utilities.createLongTextField();
  private JCheckBox obsolete = Utilities.createCheckBox(
      INFO_CTRL_PANEL_OBJECTCLASS_OBSOLETE_LABEL.get());
  private DoubleAddRemovePanel<AttributeType> attributes;

  private Schema schema;

  private Component relativeComponent;

  /**
   * Constructor of the new object class panel.
   * @param relativeComponent the component relative to which the dialog
   * containing this panel must be centered.
   */
  public NewObjectClassPanel(Component relativeComponent)
  {
    super();
    this.relativeComponent = relativeComponent;
    createLayout();
  }

  @Override
  public LocalizableMessage getTitle()
  {
    return INFO_CTRL_PANEL_NEW_OBJECTCLASS_PANEL_TITLE.get();
  }

  @Override
  public Component getPreferredFocusComponent()
  {
    return name;
  }

  @Override
  public void configurationChanged(ConfigurationChangeEvent ev)
  {
    final ServerDescriptor desc = ev.getNewDescriptor();
    Schema s = desc.getSchema();

    final boolean[] repack = {schema == null};
    final boolean[] error = {false};

    final boolean schemaChanged;
    if (schema != null && s != null)
    {
      schemaChanged = !ServerDescriptor.areSchemasEqual(s, schema);
    }
    else if (schema == null && s != null)
    {
      schemaChanged = true;
    }
    else if (s == null && schema != null)
    {
      schemaChanged = false;
    }
    else
    {
      schemaChanged = false;
    }
    if (schemaChanged)
    {
      schema = s;
    }
    else if (schema == null)
    {
      updateErrorPane(errorPane,
          ERR_CTRL_PANEL_SCHEMA_NOT_FOUND_SUMMARY.get(),
          ColorAndFontConstants.errorTitleFont,
          ERR_CTRL_PANEL_SCHEMA_NOT_FOUND_DETAILS.get(),
          ColorAndFontConstants.defaultFont);
      repack[0] = true;
      error[0] = true;
    }
    SwingUtilities.invokeLater(new Runnable()
    {
      @Override
      public void run()
      {
        setEnabledOK(!error[0]);
        errorPane.setVisible(error[0]);
        if (schema != null && schemaChanged)
        {
          superiors.setSchema(schema);
          updateAttributes();
        }
        if (repack[0])
        {
          packParentDialog();
          if (relativeComponent != null)
          {
            Utilities.centerGoldenMean(
                Utilities.getParentDialog(NewObjectClassPanel.this),
                relativeComponent);
          }
        }
      }
    });
    if (!error[0])
    {
      updateErrorPaneAndOKButtonIfAuthRequired(desc,
          isLocal() ?
   INFO_CTRL_PANEL_AUTHENTICATION_REQUIRED_TO_CREATE_OBJECTCLASS_SUMMARY.get() :
     INFO_CTRL_PANEL_CANNOT_CONNECT_TO_REMOTE_DETAILS.get(desc.getHostname()));
    }
  }

  @Override
  public void okClicked()
  {
    ArrayList<LocalizableMessage> errors = new ArrayList<>();
    for (JLabel label : labels)
    {
      setPrimaryValid(label);
    }
    String n = getText(name);
    LocalizableMessageBuilder err = new LocalizableMessageBuilder();
    if (n.length() == 0)
    {
      errors.add(ERR_CTRL_PANEL_OBJECTCLASS_NAME_REQUIRED.get());
    }
    else if (!StaticUtils.isValidSchemaElement(n, 0, n.length(), err))
    {
      errors.add(ERR_CTRL_PANEL_INVALID_OBJECTCLASS_NAME.get(err));
      err = new LocalizableMessageBuilder();
    }
    else
    {
      LocalizableMessage elementType = NewAttributePanel.getSchemaElementType(n, schema);
      if (elementType != null)
      {
        errors.add(ERR_CTRL_PANEL_OBJECTCLASS_NAME_ALREADY_IN_USE.get(n, elementType));
      }
    }

    n = getText(oid);
    if (n.length() > 0)
    {
      if (!StaticUtils.isValidSchemaElement(n, 0, n.length(), err))
      {
        errors.add(ERR_CTRL_PANEL_OID_NOT_VALID.get(err));
        err = new LocalizableMessageBuilder();
      }
      else
      {
        LocalizableMessage elementType = NewAttributePanel.getSchemaElementType(n, schema);
        if (elementType != null)
        {
          errors.add(ERR_CTRL_PANEL_OID_ALREADY_IN_USE.get(n, elementType));
        }
      }
    }

    if (getText(aliases).length() > 0)
    {
      String[] al = aliases.getText().split(",");
      if (al.length > 0)
      {
        for (String alias : al)
        {
          if (alias.trim().length() == 0)
          {
            errors.add(ERR_CTRL_PANEL_EMPTY_ALIAS.get());
          }
          else
          {
            LocalizableMessage elementType = NewAttributePanel.getSchemaElementType(
                alias, schema);
            if (elementType != null)
            {
              errors.add(ERR_CTRL_PANEL_ALIAS_ALREADY_IN_USE.get(n, elementType));
            }
          }
        }
      }
    }

    checkCompatibleSuperiors(getObjectClassSuperiors(), getObjectClassType(),
        errors);

    ProgressDialog dlg = new ProgressDialog(
        Utilities.createFrame(),
        Utilities.getParentDialog(this),
        INFO_CTRL_PANEL_NEW_OBJECTCLASS_PANEL_TITLE.get(), getInfo());
    NewSchemaElementsTask newTask = null;
    if (errors.isEmpty())
    {
      LinkedHashSet<AttributeType> attributes = new LinkedHashSet<>(0);
      LinkedHashSet<ObjectClass> ocs = new LinkedHashSet<>(1);
      ocs.add(getObjectClass());
      newTask = new NewSchemaElementsTask(getInfo(), dlg, ocs, attributes);
      for (Task task : getInfo().getTasks())
      {
        task.canLaunch(newTask, errors);
      }
      for (ConfigurationElementCreatedListener listener :
        getConfigurationElementCreatedListeners())
      {
        newTask.addConfigurationElementCreatedListener(listener);
      }
    }
    if (errors.isEmpty())
    {
      String ocName = getText(name);
      launchOperation(newTask,
          INFO_CTRL_PANEL_CREATING_OBJECTCLASS_SUMMARY.get(ocName),
          INFO_CTRL_PANEL_CREATING_OBJECTCLASS_COMPLETE.get(),
          INFO_CTRL_PANEL_CREATING_OBJECTCLASS_SUCCESSFUL.get(ocName),
          ERR_CTRL_PANEL_CREATING_OBJECTCLASS_ERROR_SUMMARY.get(),
          ERR_CTRL_PANEL_CREATING_OBJECTCLASS_ERROR_DETAILS.get(ocName),
          null,
          dlg);
      dlg.setVisible(true);
      name.setText("");
      oid.setText("");
      description.setText("");
      aliases.setText("");
      superiors.setSelectedSuperiors(Collections.singleton(CoreSchema.getTopObjectClass()));
      attributes.getAvailableListModel().addAll(
          attributes.getSelectedListModel1().getData());
      attributes.getAvailableListModel().addAll(
          attributes.getSelectedListModel2().getData());
      attributes.getSelectedListModel1().clear();
      attributes.getSelectedListModel2().clear();
      name.grabFocus();
      Utilities.getParentDialog(this).setVisible(false);
    }
    else
    {
      displayErrorDialog(errors);
    }
  }

  private void updateAttributes()
  {
    int[][] selected =
    {
      attributes.getAvailableList().getSelectedIndices(),
      attributes.getSelectedList1().getSelectedIndices(),
      attributes.getSelectedList2().getSelectedIndices()
    };
    JList<?>[] lists =
    {
        attributes.getAvailableList(),
        attributes.getSelectedList1(),
        attributes.getSelectedList2()
    };
    attributes.getAvailableListModel().clear();
    Collection<AttributeType> allAttrs = schema.getAttributeTypes();
    attributes.getAvailableListModel().addAll(allAttrs);

    HashSet<AttributeType> toDelete = new HashSet<>();
    for (AttributeType attr : attributes.getSelectedListModel1().getData())
    {
      if (!allAttrs.contains(attr))
      {
        toDelete.add(attr);
      }
      else
      {
        attributes.getAvailableListModel().remove(attr);
      }
    }
    for (AttributeType attr : toDelete)
    {
      attributes.getSelectedListModel1().remove(attr);
    }

    toDelete = new HashSet<>();
    for (AttributeType attr : attributes.getSelectedListModel2().getData())
    {
      if (!allAttrs.contains(attr))
      {
        toDelete.add(attr);
      }
      else
      {
        attributes.getAvailableListModel().remove(attr);
      }
    }
    for (AttributeType attr : toDelete)
    {
      attributes.getSelectedListModel1().remove(attr);
    }

    int i = 0;
    for (int[] sel : selected)
    {
      if (sel != null)
      {
        ArrayList<Integer> indexes = new ArrayList<>();
        for (int element : sel)
        {
          if (element < lists[i].getModel().getSize())
          {
            indexes.add(element);
          }
        }
        int[] newSelection = new int[indexes.size()];
        for (int j=0; j<newSelection.length; j++)
        {
          newSelection[j] = indexes.get(j);
        }
        lists[i].setSelectedIndices(newSelection);
      }
      i++;
    }
  }

  /** Creates the layout of the panel (but the contents are not populated here). */
  private void createLayout()
  {
    GridBagConstraints gbc = new GridBagConstraints();

    Utilities.setRequiredIcon(lName);

    gbc.gridwidth = 2;
    gbc.gridy = 0;
    addErrorPane(gbc);

    gbc.gridy ++;
    gbc.gridwidth = 1;
    gbc.weighty = 0.0;
    gbc.gridx = 1;
    gbc.anchor = GridBagConstraints.EAST;
    gbc.fill = GridBagConstraints.NONE;
    JLabel requiredLabel = createRequiredLabel();
    gbc.insets.bottom = 10;
    add(requiredLabel, gbc);

    gbc.gridy ++;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.anchor = GridBagConstraints.WEST;
    gbc.insets.bottom = 0;

    SuperiorObjectClassesChangedListener listener =
      new SuperiorObjectClassesChangedListener()
    {
      @Override
      public void parentObjectClassesChanged(
          SuperiorObjectClassesChangedEvent ev)
      {
        // Remove the previous inherited attributes.
        for (AttributeType attr : inheritedRequiredAttributes)
        {
          attributes.getAvailableListModel().add(attr);
          attributes.getSelectedListModel1().remove(attr);
        }
        for (AttributeType attr : inheritedOptionalAttributes)
        {
          attributes.getAvailableListModel().add(attr);
          attributes.getSelectedListModel2().remove(attr);
        }

        inheritedOptionalAttributes.clear();
        inheritedRequiredAttributes.clear();
        for (ObjectClass oc : superiors.getSelectedSuperiors())
        {
          inheritedRequiredAttributes.addAll(oc.getRequiredAttributes());
          inheritedOptionalAttributes.addAll(oc.getOptionalAttributes());
        }
        for (AttributeType attr : inheritedRequiredAttributes)
        {
          attributes.getAvailableListModel().remove(attr);
          attributes.getSelectedListModel1().add(attr);
        }
        for (AttributeType attr : inheritedOptionalAttributes)
        {
          attributes.getAvailableListModel().remove(attr);
          attributes.getSelectedListModel2().add(attr);
        }
        attributes.getAvailableListModel().fireContentsChanged(
            attributes.getAvailableList(), 0,
            attributes.getAvailableList().getModel().getSize() - 1);
        attributes.getSelectedListModel1().fireContentsChanged(
            attributes.getSelectedList1(), 0,
            attributes.getSelectedList1().getModel().getSize() - 1);
        attributes.getSelectedListModel2().fireContentsChanged(
            attributes.getSelectedList2(), 0,
            attributes.getSelectedList2().getModel().getSize() - 1);

        Collection<AttributeType> unmovableItems = new ArrayList<>(inheritedRequiredAttributes);
        unmovableItems.addAll(inheritedOptionalAttributes);
        attributes.setUnmovableItems(unmovableItems);

        if (ev.getNewObjectClasses().size() > 1)
        {
          lSuperior.setText(
              INFO_CTRL_PANEL_OBJECTCLASS_PARENTS_LABEL.get().toString());
        }
        else
        {
          lSuperior.setText(
              INFO_CTRL_PANEL_OBJECTCLASS_PARENT_LABEL.get().toString());
        }
      }
    };
    superiors.addParentObjectClassesChangedListener(listener);

    DefaultComboBoxModel<ObjectClassType> model = new DefaultComboBoxModel<>();
    for (ObjectClassType t : ObjectClassType.values())
    {
      model.addElement(t);
    }
    type.setModel(model);
    type.setSelectedItem(ObjectClassType.STRUCTURAL);
    SchemaElementComboBoxCellRenderer renderer = new
    SchemaElementComboBoxCellRenderer(type);
    type.setRenderer(renderer);

    attributes = new DoubleAddRemovePanel<>(0, AttributeType.class);
    Comparator<AttributeType> comparator = new Comparator<AttributeType>()
    {
      @Override
      public int compare(AttributeType attr1, AttributeType attr2)
      {
        return attr1.getNameOrOID().toLowerCase().compareTo(
            attr2.getNameOrOID().toLowerCase());
      }
    };
    attributes.getAvailableListModel().setComparator(comparator);
    attributes.getSelectedListModel1().setComparator(comparator);
    attributes.getSelectedListModel2().setComparator(comparator);

    Component[] basicComps = {name, oid, description, superiors};
    JLabel[] basicLabels = {lName, lOID, lDescription, lSuperior};
    JLabel[] basicInlineHelp = new JLabel[] {null, null, null, null};
    add(basicLabels, basicComps, basicInlineHelp, this, gbc);

    gbc.gridx = 0;
    gbc.weightx = 0.0;
    gbc.insets.left = 0;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    add(lAttributes, gbc);

    gbc.gridx ++;
    gbc.fill = GridBagConstraints.BOTH;
    gbc.weightx = 1.0;
    gbc.weighty = 1.0;
    gbc.insets.left = 10;
    add(attributes, gbc);
    attributes.getAvailableLabel().setText(
        INFO_CTRL_PANEL_ADDREMOVE_AVAILABLE_ATTRIBUTES.get().toString());
    attributes.getSelectedLabel1().setText(
        INFO_CTRL_PANEL_ADDREMOVE_REQUIRED_ATTRIBUTES.get().toString());
    attributes.getSelectedLabel2().setText(
        INFO_CTRL_PANEL_ADDREMOVE_OPTIONAL_ATTRIBUTES.get().toString());
    AttributeTypeCellRenderer listRenderer = new AttributeTypeCellRenderer();
    attributes.getAvailableList().setCellRenderer(listRenderer);
    attributes.getSelectedList1().setCellRenderer(listRenderer);
    attributes.getSelectedList2().setCellRenderer(listRenderer);

    gbc.gridy ++;
    gbc.weighty = 0.0;
    gbc.insets.top = 3;
    JLabel explanation = Utilities.createInlineHelpLabel(
        INFO_CTRL_PANEL_INHERITED_ATTRIBUTES_HELP.get());
    gbc.insets.top = 3;
    add(explanation, gbc);

    final BasicExpander expander = new BasicExpander(
        INFO_CTRL_PANEL_EXTRA_OPTIONS_EXPANDER.get());

    obsolete.setText("Obsolete");

    Component[] comps = {aliases, origin, file, type, obsolete};
    JLabel[] labels = {lAliases, lOrigin, lFile, lType, null};
    JLabel[] inlineHelps = {
        Utilities.createInlineHelpLabel(
            INFO_CTRL_PANEL_SEPARATED_WITH_COMMAS_HELP.get()), null,
        Utilities.createInlineHelpLabel(
            INFO_CTRL_PANEL_SCHEMA_FILE_OBJECTCLASS_HELP.get(File.separator)),
            null, null};
    gbc.gridwidth = 2;
    gbc.gridx = 0;
    gbc.weighty = 0.0;
    gbc.insets.left = 0;
    gbc.gridy ++;
    add(expander, gbc);
    final JPanel p = new JPanel(new GridBagLayout());
    gbc.insets.left = 15;
    gbc.gridy ++;
    add(p, gbc);
    gbc.gridy ++;
    p.setOpaque(false);

    GridBagConstraints gbc1 = new GridBagConstraints();
    gbc1.fill = GridBagConstraints.HORIZONTAL;
    gbc1.gridy = 0;

    add(labels, comps, inlineHelps, p, gbc1);
    ChangeListener changeListener = new ChangeListener()
    {
      @Override
      public void stateChanged(ChangeEvent e)
      {
        p.setVisible(expander.isSelected());
      }
    };
    expander.addChangeListener(changeListener);
    expander.setSelected(false);
    changeListener.stateChanged(null);

    file.setText(ConfigConstants.FILE_USER_SCHEMA_ELEMENTS);
  }

  private String getText(JTextField textField)
  {
    return textField.getText().trim();
  }

  private String getOID()
  {
    String o = getText(oid);
    if (o.length() == 0)
    {
      o = getText(name) + "-oid";
    }
    return o;
  }

  private Set<ObjectClass> getObjectClassSuperiors()
  {
    return superiors.getSelectedSuperiors();
  }

  private Map<String, List<String>> getExtraProperties()
  {
    Map<String, List<String>> map = new HashMap<>();
    String f = getText(file);
    if (f.length() > 0)
    {
      map.put(ServerConstants.SCHEMA_PROPERTY_FILENAME, newArrayList(f));
    }
    String or = getText(origin);
    if (or.length() > 0)
    {
      map.put(ServerConstants.SCHEMA_PROPERTY_ORIGIN, newArrayList(or));
    }
    return map;
  }

  private List<String> getAliases()
  {
    List<String> al = new ArrayList<>();
    String s = getText(aliases);
    if (s.length() > 0)
    {
      for (String alias : s.split(","))
      {
        al.add(alias.trim());
      }
    }
    return al;
  }

  private ArrayList<String> getAllNames()
  {
    ArrayList<String> al = new ArrayList<>();
    al.add(getText(name));
    al.addAll(getAliases());
    return al;
  }

  private String getDescription()
  {
    return getText(description);
  }

  private ObjectClass getObjectClass()
  {
    return new SchemaBuilder(schema).buildObjectClass(getOID())
        .names(getAllNames())
        .description(getDescription())
        .superiorObjectClasses(getNameOrOIDsForOCs(getObjectClassSuperiors()))
        .requiredAttributes(getNameOrOIDsForATs(getRequiredAttributes()))
        .optionalAttributes(getNameOrOIDsForATs(getOptionalAttributes()))
        .type(getObjectClassType())
        .obsolete(obsolete.isSelected())
        .extraProperties(getExtraProperties())
        .addToSchema()
        .toSchema()
        .getObjectClass(getOID());
  }

  private ObjectClassType getObjectClassType()
  {
    return (ObjectClassType)type.getSelectedItem();
  }

  private Set<AttributeType> getRequiredAttributes()
  {
    return intersect(attributes.getSelectedListModel1().getData(), inheritedRequiredAttributes);
  }

  private Set<AttributeType> getOptionalAttributes()
  {
    return intersect(attributes.getSelectedListModel2().getData(), inheritedOptionalAttributes);
  }

  private Set<AttributeType> intersect(Set<AttributeType> set1, Set<AttributeType> set2)
  {
    HashSet<AttributeType> attrs = new HashSet<>(set1);
    attrs.removeAll(set2);
    return attrs;
  }

  /**
   * A renderer for the attribute lists.  The renderer basically marks the
   * inherited attributes with an asterisk.
   */
  private class AttributeTypeCellRenderer implements ListCellRenderer
  {
    private ListCellRenderer defaultRenderer;

    /** Renderer constructor. */
    public AttributeTypeCellRenderer()
    {
      defaultRenderer = attributes.getAvailableList().getCellRenderer();
    }

    @Override
    public Component getListCellRendererComponent(JList list, Object value,
        int index, boolean isSelected, boolean cellHasFocus)
    {
      if (value instanceof AttributeType)
      {
        AttributeType attr = (AttributeType)value;
        if (inheritedOptionalAttributes.contains(value) ||
            inheritedRequiredAttributes.contains(value))
        {
          value = attr.getNameOrOID()+ " (*)";
        }
        else
        {
          value = attr.getNameOrOID();
        }
      }
      return defaultRenderer.getListCellRendererComponent(list, value, index,
          isSelected, cellHasFocus);
    }
  }
}
